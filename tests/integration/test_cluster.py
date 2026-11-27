import concurrent.futures
import contextlib
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import uuid


JAR = os.environ["RESERVATION_JAR"]
JAVA = os.environ["RESERVATION_JAVA"]
LOOPBACK = "127.0.0.1"
NODES = ("a", "b", "c")
ITEMS = {"projector": "a", "screen": "a", "camera": "b", "speaker": "b"}
STATUSES = {"COMMITTED", "ABORTED", "PENDING", "ID_CONFLICT", "INVALID_REQUEST"}


def next_ports():
    # Keep the sockets open until all nodes have distinct ports, then release them for the servers.
    with contextlib.ExitStack() as sockets:
        ports = {}
        for node in NODES:
            listener = sockets.enter_context(socket.socket())
            listener.bind((LOOPBACK, 0))
            ports[node] = listener.getsockname()[1]
        return ports


def eventually(check, diagnostic, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if result := check():
            return result
        time.sleep(0.025)
    raise AssertionError(diagnostic())


def wire_text(value):
    data = value.encode()
    return struct.pack(">i", len(data)) + data


class Cluster:
    def __init__(self, root):
        self.root = root
        self.ports = next_ports()
        self.processes = {}
        self.logs = {}
        self.read_timeout_ms = 500
        self.write_config(self.root / "client")

    def write_config(self, directory, fault=None):
        settings = {
            "client.coordinator": "a",
            "tcp.connectTimeoutMs": 300,
            "tcp.readTimeoutMs": self.read_timeout_ms,
            "tx.voteTimeoutMs": 3000,
            "tx.retryDelayMs": 100,
        }
        for node, port in self.ports.items():
            settings[f"node.{node}.host"] = LOOPBACK
            settings[f"node.{node}.bind"] = LOOPBACK
            settings[f"node.{node}.port"] = port
        for item, owner in ITEMS.items():
            settings[f"resource.{item}.owner"] = owner
        if fault:
            node, point, tx_id = fault
            settings.update({
                "fault.node": node,
                "fault.point": point,
                "fault.action": "halt",
                "fault.txId": tx_id,
            })
        config = directory / "config"
        config.mkdir(parents=True, exist_ok=True)
        (config / "cluster.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in settings.items())
        )

    def start(self, node, point=None, tx_id=None, wait_ready=True):
        self.stop(node)
        # Reuse the node directory for WAL recovery. Give each process its own output file
        # so diagnostics can identify the current run.
        directory = self.root / node
        self.write_config(directory, (node, point, tx_id) if point else None)
        log = directory / f"server-{len(list(directory.glob('server-*')))}.log"
        with log.open("wb") as output:
            process = subprocess.Popen(
                [JAVA, "-jar", JAR, "server", "--node", node],
                cwd=directory, stdout=output, stderr=subprocess.STDOUT,
            )
        self.processes[node] = process
        self.logs[node] = log

        # Wait for a real connection after startup and replay. Tests expecting a replay failure
        # skip this probe and assert the exit themselves.
        if wait_ready:
            def listening():
                if process.poll() is not None:
                    raise AssertionError(self.diagnostic(f"Node {node} exited early"))
                try:
                    with socket.create_connection((LOOPBACK, self.ports[node]), timeout=0.1):
                        return True
                except OSError:
                    return False

            eventually(listening, lambda: self.diagnostic(f"Node {node} did not listen"))
        return process

    def stop(self, node):
        process = self.processes.get(node)
        if process is not None:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)

    def close(self):
        for node in NODES:
            self.stop(node)

    def diagnostic(self, message):
        logs = [f"{node}:\n{path.read_text(errors='replace')[-4000:]}"
                for node, path in self.logs.items()]
        return message + "\n" + "\n".join(logs)

    def invoke(self, node, items, tx_id=None):
        args = [JAVA, "-jar", JAR, "reserve", "--coordinator", node]
        if tx_id:
            args.extend(("--tx-id", tx_id))
        return subprocess.run(
            [*args, *items], cwd=self.root / "client", capture_output=True,
            text=True, timeout=8, check=False,
        )

    def request(self, node, items, tx_id=None):
        result = self.invoke(node, items, tx_id)
        key_line = re.search(r"^TX_ID ([-0-9a-f]+)$", result.stdout, re.MULTILINE)
        assert key_line, self.diagnostic(str(result))
        key = key_line[1]
        assert str(uuid.UUID(key)) == key
        assert tx_id is None or tx_id == key
        statuses = [line for line in result.stdout.splitlines() if line in STATUSES]
        assert len(statuses) == 1, self.diagnostic(f"Expected a status: {result}")
        return key, statuses[0]

    def outcome(self, node, items, tx_id):
        def final_status():
            _, status = self.request(node, items, tx_id)
            return status if status != "PENDING" else None

        return eventually(final_status, lambda: self.diagnostic(f"{tx_id} remained pending"))

    def expect_fault(self, node, point, tx_id):
        process = self.processes[node]
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired as exc:
            raise AssertionError(self.diagnostic(f"Node {node} did not halt at {point}")) from exc
        assert process.returncode == 91, self.diagnostic(f"Unexpected exit: {process.returncode}")
        assert f"FAULT {point} {tx_id}" in self.logs[node].read_text(), self.diagnostic("Wrong fault")

    def wal(self, node):
        return self.root / node / "run" / node / "transactions.wal"

    def exchange(self, destination, kind, coordinator, tx_id, payload):
        # Send individual protocol frames so tests can control ordering without the CLI's polling.
        frame = bytes((1, kind)) + wire_text(coordinator) + wire_text(tx_id) + payload
        with socket.create_connection((LOOPBACK, self.ports[destination]), timeout=2) as conn:
            conn.sendall(struct.pack(">i", len(frame)) + frame)
            with conn.makefile("rb") as incoming:
                header = incoming.read(4)
                if not header:
                    return b""
                length = struct.unpack(">i", header)[0]
                return incoming.read(length)

    @staticmethod
    def reply_prefix(kind, coordinator, tx_id, sender=""):
        prefix = bytes((1, kind)) + wire_text(coordinator) + wire_text(tx_id)
        return prefix + wire_text(sender) if sender else prefix

    def status(self, body, kind, coordinator, tx_id, sender=""):
        prefix = self.reply_prefix(kind, coordinator, tx_id, sender)
        assert body.startswith(prefix), self.diagnostic(f"Unexpected response: {body!r}")
        payload = body[len(prefix):]
        assert len(payload) == 4 + struct.unpack(">i", payload[:4])[0]
        return payload[4:].decode()

    def reserve_once(self, coordinator, items, tx_id):
        payload = struct.pack(">i", len(items)) + b"".join(map(wire_text, items))
        body = self.exchange(coordinator, 1, coordinator, tx_id, payload)
        return self.status(body, 2, coordinator, tx_id)

    def participant_list(self, members):
        payload = struct.pack(">i", len(members))
        for node in members:
            payload += wire_text(node) + wire_text(LOOPBACK) + struct.pack(">i", self.ports[node])
        return payload

    def prepare(self, destination, coordinator, tx_id, items, members=("a", "b")):
        payload = wire_text(coordinator) + self.participant_list(members)
        payload += struct.pack(">i", len(items)) + b"".join(map(wire_text, items))
        return self.exchange(destination, 3, coordinator, tx_id, payload)

    def decision_query(self, destination, coordinator, sender, tx_id):
        payload = wire_text(sender) + self.participant_list(("a", "b"))
        body = self.exchange(destination, 9, coordinator, tx_id, payload)
        return self.status(body, 10, coordinator, tx_id, destination)


class ReservationTest(unittest.TestCase):
    def setUp(self):
        # Cleanup runs in reverse order. Stop the JVMs before removing directories that hold open files.
        directory = tempfile.TemporaryDirectory(prefix="reservation-test-")
        self.addCleanup(directory.cleanup)
        self.cluster = Cluster(Path(directory.name))
        self.addCleanup(self.cluster.close)

    @staticmethod
    def key():
        return str(uuid.uuid4())

    def start(self, *nodes):
        for node in nodes:
            self.cluster.start(node)

    def assert_outcome(self, expected, node, items, tx_id=None):
        tx_id = tx_id or self.key()
        self.assertEqual(self.cluster.outcome(node, items, tx_id), expected)
        return tx_id

    def assert_decision(self, expected, destination, coordinator, sender, tx_id):
        def check():
            return self.cluster.decision_query(destination, coordinator, sender, tx_id) == expected

        eventually(check, lambda: self.cluster.diagnostic(f"{destination} did not report {expected}"))

    def test_large_frame_does_not_stop_coordinator(self):
        self.start("a", "b")
        key = self.key()
        # Fit the incoming request in one frame while making the coordinator's start record too large.
        # Reuse the key afterwards to verify validation failed before the transaction was saved.
        overhead = 2 + len(wire_text("a")) + len(wire_text(key)) + 8
        item = "x" * ((1 << 20) - overhead)
        self.assertEqual(self.cluster.reserve_once("a", [item], key), "INVALID_REQUEST")
        self.assert_outcome("COMMITTED", "a", ["projector", "camera"], key)

    def test_commit_retry_and_unavailable(self):
        self.start("a", "b")
        items = ("projector", "camera")
        key, status = self.cluster.request("a", items)
        self.assertIn(status, ("PENDING", "COMMITTED"))
        self.assert_outcome("COMMITTED", "a", items, key)
        self.assert_outcome("COMMITTED", "a", items[::-1], key)
        self.assert_outcome("ID_CONFLICT", "a", ("screen",), key)
        # Losing a claim on camera must leave screen available for the later transaction.
        self.assert_outcome("ABORTED", "a", ("screen", "camera"))
        self.assert_outcome("ABORTED", "a", ("unknown",))
        self.assert_outcome("COMMITTED", "a", ("screen",))

    def test_simultaneous_conflicting_and_disjoint_requests(self):
        self.start(*NODES)
        requests = [("a", ("projector", "camera")), ("c", ("projector", "camera")),
                    ("a", ("screen",)), ("b", ("speaker",))]
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(requests)) as workers:
            futures = [workers.submit(self.cluster.outcome, node, items, self.key())
                       for node, items in requests]
            statuses = [future.result() for future in futures]
        # The conflicting pair may both abort if different nodes lock on behalf of different requests.
        # The disjoint requests should still commit.
        self.assertTrue(set(statuses[:2]) <= {"COMMITTED", "ABORTED"})
        self.assertLessEqual(statuses[:2].count("COMMITTED"), 1)
        self.assertEqual(statuses[2:], ["COMMITTED", "COMMITTED"])

    def test_duplicate_prepare_keeps_vote_after_reordering_and_restart(self):
        self.start("b")
        key = self.key()
        expected = self.cluster.reply_prefix(4, "c", key, "b")
        self.assertEqual(self.cluster.prepare("b", "c", key, ("camera",)), expected)
        self.cluster.stop("b")
        saved = self.cluster.wal("b").read_bytes()
        self.cluster.start("b")
        # Participant ordering can change on redelivery. The saved vote and lock must hold without a new record.
        self.assertEqual(self.cluster.prepare("b", "c", key, ("camera",), ("b", "a")), expected)
        self.assertEqual(self.cluster.prepare("b", "c", key, ("camera",)), expected)
        self.cluster.stop("b")
        self.assertEqual(self.cluster.wal("b").read_bytes(), saved)
        self.cluster.start("b")
        self.assert_outcome("ABORTED", "b", ("camera",))

    def test_abort_before_prepare_blocks_late_prepare_after_restart(self):
        self.start("b")
        key = self.key()
        abort = self.cluster.exchange("b", 7, "c", key, wire_text("c"))
        self.assertEqual(self.cluster.status(abort, 8, "c", key, "b"), "ABORTED")
        self.cluster.stop("b")
        saved = self.cluster.wal("b").read_bytes()
        self.cluster.start("b")
        self.assertEqual(self.cluster.exchange("b", 7, "c", key, wire_text("c")), abort)
        expected = self.cluster.reply_prefix(5, "c", key, "b")
        self.assertEqual(self.cluster.prepare("b", "c", key, ("camera",)), expected)
        self.cluster.stop("b")
        self.assertEqual(self.cluster.wal("b").read_bytes(), saved)
        self.cluster.start("b")
        self.assert_outcome("COMMITTED", "b", ("camera",))

    def test_ready_participant_rejects_wrong_sender_and_mismatched_members(self):
        self.start("b")
        key = self.key()
        vote = self.cluster.prepare("b", "c", key, ("camera",))
        self.assertEqual(vote, self.cluster.reply_prefix(4, "c", key, "b"))
        self.cluster.stop("b")
        saved = self.cluster.wal("b").read_bytes()
        self.cluster.start("b")
        wrong_sender = wire_text("a")
        changed_members = wrong_sender + self.cluster.participant_list(("a", "b", "c"))
        self.assertEqual(self.cluster.exchange("b", 7, "c", key, wrong_sender), b"")
        self.assertEqual(self.cluster.exchange("b", 9, "c", key, changed_members), b"")
        self.assertEqual(self.cluster.decision_query("b", "c", "a", key), "PENDING")
        self.cluster.stop("b")
        self.assertEqual(self.cluster.wal("b").read_bytes(), saved)

    def test_finished_transaction_retries_do_not_append_records(self):
        self.start("a", "b")
        items = ("projector", "camera")
        key = self.assert_outcome("COMMITTED", "a", items)
        for node in ("a", "b"):
            self.cluster.stop(node)
        saved = {node: self.cluster.wal(node).read_bytes() for node in ("a", "b")}
        self.start("a", "b")
        self.assertEqual(self.cluster.reserve_once("a", items, key), "COMMITTED")
        self.assertEqual(self.cluster.reserve_once("a", ("screen",), key), "ID_CONFLICT")
        self.start("a", "b")
        self.assertEqual(self.cluster.reserve_once("a", items[::-1], key), "COMMITTED")
        self.assertEqual(self.cluster.reserve_once("a", ("screen",), key), "ID_CONFLICT")
        for node, wal in saved.items():
            self.cluster.stop(node)
            self.assertEqual(self.cluster.wal(node).read_bytes(), wal)

    def test_coordinator_recovers_unknown_item_reservation_after_start(self):
        self.start("a", "b")
        key = self.key()
        self.cluster.start("c", "after-start", key)
        self.cluster.invoke("c", ("unknown",), key)
        self.cluster.expect_fault("c", "after-start", key)
        self.cluster.start("c")
        self.assert_outcome("ABORTED", "c", ("unknown",), key)
        self.assert_outcome("COMMITTED", "c", ("projector", "camera"))

    def test_late_vote_keeps_decision_call_in_flight(self):
        # Let the vote deadline expire while the PREPARE socket remains open.
        self.cluster.read_timeout_ms = 6000
        key = self.key()

        def expect_message(connection, kind):
            connection.settimeout(2)
            with connection.makefile("rb") as stream:
                length, = struct.unpack(">i", stream.read(4))
                self.assertEqual(stream.read(length)[:2], bytes((1, kind)))

        def reply(connection, kind, status=None):
            frame = self.cluster.reply_prefix(kind, "a", key, "b")
            if status is not None:
                frame += wire_text(status)
            connection.sendall(struct.pack(">i", len(frame)) + frame)

        with socket.socket() as peer:
            peer.bind((LOOPBACK, self.cluster.ports["b"]))
            peer.listen()
            peer.settimeout(6)
            self.start("a")
            self.assertEqual(self.cluster.reserve_once("a", ("camera",), key), "PENDING")
            prepare, _ = peer.accept()
            with prepare:
                expect_message(prepare, 3)
                decision, _ = peer.accept()
                with decision:
                    expect_message(decision, 7)
                    # Deliver the stale vote while the abort is waiting for ACK. The late vote
                    # must not clear that in-flight call and cause a duplicate abort.
                    reply(prepare, 4)
                    peer.settimeout(0.5)
                    with self.assertRaises(socket.timeout):
                        peer.accept()
                    reply(decision, 8, "ABORTED")
            self.assert_outcome("ABORTED", "a", ("camera",), key)

    def test_unseen_decision_query_closes_key(self):
        self.start(*NODES)
        key = self.key()
        query = self.cluster.decision_query("c", "c", "b", key)
        self.assertEqual(query, "ABORTED")
        self.cluster.start("c")
        self.assert_outcome("ABORTED", "c", ("projector", "camera"), key)
        self.assert_outcome("COMMITTED", "c", ("projector", "camera"))

    def test_all_ready_locks_survive_participant_restart(self):
        self.start("a", "b")
        key = self.key()
        items = ("projector", "camera")
        self.cluster.start("c", "after-all-yes", key)
        self.cluster.invoke("c", items, key)
        self.cluster.expect_fault("c", "after-all-yes", key)
        # With both votes durable and the coordinator down, a restarted participant must
        # reconstruct its lock and stay blocked until the coordinator's recovery abort.
        self.cluster.start("a")
        self.assertEqual(self.cluster.decision_query("b", "c", "a", key), "PENDING")
        self.assert_outcome("ABORTED", "a", ("projector",))
        self.assert_outcome("ABORTED", "b", ("camera",))
        self.cluster.start("c")
        self.assert_outcome("ABORTED", "c", items, key)
        self.assert_outcome("COMMITTED", "c", items)

    def test_commit_survives_coordinator_crash_before_delivery(self):
        self.start("a", "b")
        key = self.key()
        items = ("projector", "camera")
        self.cluster.start("c", "after-decision", key)
        self.cluster.invoke("c", items, key)
        self.cluster.expect_fault("c", "after-decision", key)
        self.cluster.start("c")
        self.assert_outcome("COMMITTED", "c", items, key)
        self.assert_outcome("ABORTED", "a", ("projector",))
        self.assert_outcome("ABORTED", "b", ("camera",))

    def test_coordinator_recovers_after_start_and_after_end(self):
        self.start("a", "b")
        items = ("projector", "camera")
        for point, expected in (("after-start", "ABORTED"), ("after-end", "COMMITTED")):
            with self.subTest(point=point):
                key = self.key()
                self.cluster.start("c", point, key)
                self.cluster.invoke("c", items, key)
                self.cluster.expect_fault("c", point, key)
                self.cluster.start("c")
                self.assert_outcome(expected, "c", items, key)

    def test_peer_votes_abort_after_partial_prepare(self):
        self.start("a", "b")
        key = self.key()
        items = ("projector", "camera")
        point = "after-first-prepare"
        self.cluster.start("c", point, key)
        self.cluster.invoke("c", items, key)
        self.cluster.expect_fault("c", point, key)
        self.assertEqual(self.cluster.decision_query("b", "c", "a", key), "VOTE_ABORT")
        self.assert_decision("ABORTED", "a", "c", "b", key)
        self.assert_outcome("COMMITTED", "a", ("projector",))
        self.cluster.start("c")
        self.assert_outcome("ABORTED", "c", items, key)

    def test_peer_learns_commit_after_partial_delivery(self):
        self.start("a", "b")
        key = self.key()
        items = ("projector", "camera")
        point = "after-first-decision"
        self.cluster.start("c", point, key)
        self.cluster.invoke("c", items, key)
        self.cluster.expect_fault("c", point, key)
        self.assert_decision("COMMITTED", "b", "c", "a", key)
        self.cluster.start("c")
        self.assert_outcome("COMMITTED", "c", items, key)

    def test_participant_restarts_before_vote(self):
        self.start("a")
        key = self.key()
        items = ("projector", "camera")
        self.cluster.start("b", "before-vote", key)
        self.assertEqual(self.cluster.reserve_once("a", items, key), "PENDING")
        self.cluster.expect_fault("b", "before-vote", key)
        self.assert_decision("ABORTED", "a", "a", "b", key)
        self.cluster.start("b")
        self.assert_outcome("ABORTED", "a", items, key)
        self.assert_outcome("COMMITTED", "a", items)

    def test_participant_restarts_after_durable_vote(self):
        self.start("a")
        key = self.key()
        items = ("projector", "camera")
        self.cluster.start("b", "after-vote", key)
        self.assertEqual(self.cluster.reserve_once("a", items, key), "PENDING")
        self.cluster.expect_fault("b", "after-vote", key)
        self.cluster.start("b")
        self.assert_outcome("COMMITTED", "a", items, key)

    def check_participant_recovers_decision(self, point):
        self.start("a")
        key = self.key()
        items = ("projector", "camera")
        self.cluster.start("b", point, key)
        self.assertEqual(self.cluster.reserve_once("a", items, key), "PENDING")
        self.cluster.expect_fault("b", point, key)
        # In both crash windows the coordinator has no ACK yet, so the client still sees PENDING.
        # After restart, redelivery must finish the same transaction and keep the camera reserved.
        self.assertEqual(self.cluster.reserve_once("a", items, key), "PENDING")
        self.cluster.start("b")
        self.assert_outcome("COMMITTED", "a", items, key)
        self.assert_outcome("ABORTED", "b", ("camera",))

    def test_participant_recovers_before_decision_log(self):
        self.check_participant_recovers_decision("before-decision-log")

    def test_participant_repeats_ack_after_crash(self):
        self.check_participant_recovers_decision("after-apply")

    def test_replay_discards_partial_tail_and_rejects_bad_crc(self):
        self.start("a", "b")
        items = ("projector", "camera")
        key = self.assert_outcome("COMMITTED", "a", items)
        self.cluster.stop("a")
        wal = self.cluster.wal("a")
        saved = wal.read_bytes()
        # First append a partial record. Then damage a checksum in the complete log to
        # distinguish recoverable truncation from corruption that must stop startup.
        wal.write_bytes(saved + struct.pack(">i", 24) + b"short")
        self.cluster.start("a")
        self.assert_outcome("COMMITTED", "a", items, key)
        self.cluster.stop("a")
        self.assertEqual(wal.read_bytes(), saved)
        wal.write_bytes(saved[:-1] + bytes([saved[-1] ^ 1]))
        process = self.cluster.start("a", wait_ready=False)
        self.assertNotEqual(process.wait(timeout=8), 0)
        self.assertRegex(self.cluster.logs["a"].read_text().lower(), r"crc|checksum")
