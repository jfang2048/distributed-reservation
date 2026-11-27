package reservation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import reservation.TransactionLog.Entry;
import reservation.TransactionLog.Kind;
import reservation.TransactionLog.Role;

import static reservation.Server.*;

final class Coordinator {
    private final Server server;
    private final Map<TxId, Transaction> transactions = new HashMap<>();
    private final Set<Transaction> active = new LinkedHashSet<>();

    Coordinator(Server server) {
        this.server = server;
    }

    Message reserve(Message request) throws IOException {
        TxId id = request.id();
        require(server.node.equals(id.coordinator()), "Wrong coordinator");
        // Compare retries by resource set. Expose the final result only after every participant has acknowledged it.
        List<String> items = request.items().stream().sorted().toList();
        Transaction tx = transactions.get(id);
        if (tx != null) {
            String result = tx.items != null && !tx.items.equals(items) ? "ID_CONFLICT"
                    : tx.ended ? tx.decision : PENDING;
            return Message.reply(Type.RESULT, id, "", result);
        }
        tx = new Transaction(id, items, group(items));
        Entry start = new Entry(Role.COORDINATOR, Kind.START_2PC, id, tx.endpoints, tx.items);
        // Check that the start record and later protocol messages fit before accepting the transaction durably.
        try {
            TransactionLog.check(start);
            for (Endpoint endpoint : tx.endpoints) {
                Wire.check(prepareMessage(tx, endpoint.id()));
                Wire.check(new Message(Type.DECISION_REQUEST, id, endpoint.id(), tx.endpoints, List.of(), ""));
            }
        } catch (IOException oversized) {
            return Message.reply(Type.RESULT, id, "", "INVALID_REQUEST");
        }
        server.persist(start);
        transactions.put(id, tx);
        server.faults.hit("after-start", id);
        if (tx.endpoints.isEmpty()) {
            decide(tx, ABORTED);
            finish(tx);
            return Message.reply(Type.RESULT, id, "", ABORTED);
        }
        tx.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(server.config.voteTimeoutMs());
        active.add(tx);
        return Message.reply(Type.RESULT, id, "", PENDING);
    }

    Message query(Message request) throws IOException {
        TxId id = request.id();
        require(includes(request.participants(), request.sender()),
                "Decision query sender is outside the participant list");
        Transaction tx = transactions.get(id);
        if (tx == null) {
            // Once a peer learns abort, a delayed first RESERVE must not reopen this transaction key.
            server.persist(record(Kind.GLOBAL_ABORT, id));
            tx = tombstone(id);
            transactions.put(id, tx);
        } else if (tx.items != null) {
            require(sameEndpoints(tx.endpoints, request.participants()), "Decision query uses a different participant list");
        }
        return Message.reply(Type.DECISION_REPLY, id, server.node, tx.decision == null ? PENDING : tx.decision);
    }

    void tick(long now) throws IOException {
        for (Transaction tx : active) {
            if (tx.decision == null && now - tx.deadline >= 0) {
                decide(tx, ABORTED);
            }
            if (now - tx.nextSend >= 0) {
                tx.nextSend = server.retryAt(now);
                sendPhase(tx);
            }
        }
    }

    private void sendPhase(Transaction tx) {
        boolean preparing = tx.decision == null;
        String firstReply = preparing ? "after-first-prepare" : "after-first-decision";
        boolean stopAfterFirst = server.faults.matches(firstReply, tx.id);
        Message decision = preparing ? null : Message.simple(
                COMMITTED.equals(tx.decision) ? Type.GLOBAL_COMMIT : Type.GLOBAL_ABORT, tx.id, server.node);
        // Retry only peers with no recorded reply or call in flight for this phase. For a first-reply
        // fault, send to one peer at a time so the injected crash can leave the rest unanswered.
        for (Endpoint endpoint : tx.endpoints) {
            String peer = endpoint.id();
            if (stopAfterFirst && !tx.waiting.isEmpty()) {
                break;
            }
            if (tx.replied.contains(peer) || !tx.waiting.add(peer)) {
                continue;
            }
            Message request = preparing ? prepareMessage(tx, peer) : decision;
            if (!server.send(endpoint, request, reply -> receive(tx, peer, preparing, reply))) {
                tx.waiting.remove(peer);
            }
        }
    }

    private Message prepareMessage(Transaction tx, String peer) {
        return new Message(Type.PREPARE, tx.id, server.node, tx.endpoints, tx.groups.get(peer), "");
    }

    private void receive(Transaction tx, String peer, boolean preparing, Message reply) throws IOException {
        // A late vote belongs to the old phase, even if a decision call to that peer is in flight.
        if (tx.ended || preparing != (tx.decision == null)) {
            return;
        }
        tx.waiting.remove(peer);
        if (reply == null) {
            return;
        }
        if (preparing) {
            vote(tx, peer, reply.type());
        } else if (reply.type() == Type.ACK && tx.decision.equals(reply.status()) && tx.replied.add(peer)) {
            server.faults.hit("after-first-decision", tx.id);
            if (tx.replied.size() == tx.endpoints.size()) {
                finish(tx);
            }
        }
    }

    private void vote(Transaction tx, String peer, Type vote) throws IOException {
        if (vote != Type.VOTE_COMMIT && vote != Type.VOTE_ABORT) {
            return;
        }
        server.faults.hit("after-first-prepare", tx.id);
        // Check the deadline here as well as in tick, since the final vote can arrive between ticks.
        if (vote == Type.VOTE_ABORT || System.nanoTime() - tx.deadline >= 0) {
            decide(tx, ABORTED);
        } else if (tx.replied.add(peer) && tx.replied.size() == tx.endpoints.size()) {
            server.faults.hit("after-all-yes", tx.id);
            decide(tx, COMMITTED);
        }
    }

    private void decide(Transaction tx, String decision) throws IOException {
        // Force the decision before making it available to queries or switching from votes to ACKs.
        server.persist(record(COMMITTED.equals(decision) ? Kind.GLOBAL_COMMIT : Kind.GLOBAL_ABORT, tx.id));
        tx.decision = decision;
        tx.waiting.clear();
        tx.replied.clear();
        tx.nextSend = System.nanoTime();
        server.faults.hit("after-decision", tx.id);
    }

    private void finish(Transaction tx) throws IOException {
        server.persist(record(Kind.END, tx.id));
        tx.ended = true;
        active.remove(tx);
        tx.replied.clear();
        server.faults.hit("after-end", tx.id);
    }

    void replay(Entry entry) throws IOException {
        TxId id = entry.id();
        checkReplay(server.node.equals(id.coordinator()), id);
        Transaction tx = transactions.get(id);
        switch (entry.kind()) {
            case START_2PC -> {
                checkReplay(tx == null, id);
                // Recovery must address the original participants. Reject configuration changes that alter their endpoints.
                tx = new Transaction(id, entry.items(), group(entry.items()));
                checkReplay(sameEndpoints(tx.endpoints, entry.participants()), id);
                transactions.put(id, tx);
            }
            case GLOBAL_COMMIT, GLOBAL_ABORT -> {
                if (tx == null && entry.kind() == Kind.GLOBAL_ABORT) {
                    transactions.put(id, tombstone(id));
                } else {
                    checkReplay(tx != null && tx.decision == null, id);
                    checkReplay(entry.kind() != Kind.GLOBAL_COMMIT || !tx.endpoints.isEmpty(), id);
                    tx.decision = entry.kind() == Kind.GLOBAL_COMMIT ? COMMITTED : ABORTED;
                }
            }
            case END -> {
                checkReplay(tx != null && tx.decision != null && !tx.ended, id);
                tx.ended = true;
            }
            default -> checkReplay(false, id);
        }
    }

    void recover() throws IOException {
        // Without a durable decision, recover as abort. Otherwise retransmit the saved decision
        // for unfinished transactions because individual ACKs are tracked only in memory.
        for (Transaction tx : transactions.values()) {
            if (tx.ended) {
                continue;
            }
            if (tx.decision == null) {
                decide(tx, ABORTED);
            }
            if (tx.endpoints.isEmpty()) {
                finish(tx);
            } else {
                active.add(tx);
            }
        }
    }

    private Map<String, List<String>> group(List<String> items) {
        // One unknown item invalidates the whole request. An empty map leads to a local abort.
        Map<String, List<String>> groups = new TreeMap<>();
        for (String item : items) {
            String owner = server.config.owner(item);
            if (owner == null) {
                return Map.of();
            }
            groups.computeIfAbsent(owner, key -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    private Transaction tombstone(TxId id) {
        Transaction tx = new Transaction(id, null, Map.of());
        tx.decision = ABORTED;
        tx.ended = true;
        return tx;
    }

    private static Entry record(Kind kind, TxId id) {
        return new Entry(Role.COORDINATOR, kind, id, List.of(), List.of());
    }

    private final class Transaction {
        final TxId id;
        final List<String> items;
        final Map<String, List<String>> groups;
        final List<Endpoint> endpoints;
        final Set<String> replied = new HashSet<>();
        final Set<String> waiting = new HashSet<>();
        long deadline;
        long nextSend = System.nanoTime();
        String decision;
        boolean ended;

        Transaction(TxId id, List<String> items, Map<String, List<String>> groups) {
            this.id = id;
            this.items = items;
            this.groups = groups;
            endpoints = groups.keySet().stream().map(server.config::endpoint).toList();
        }
    }
}
