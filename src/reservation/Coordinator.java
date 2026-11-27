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
        List<String> items = request.items().stream().sorted().toList();
        require(!items.isEmpty() && new HashSet<>(items).size() == items.size(), "Invalid item list");
        Transaction tx = transactions.get(id);
        if (tx != null) {
            String result = tx.items != null && !tx.items.equals(items) ? "ID_CONFLICT"
                    : tx.ended ? tx.decision : PENDING;
            return Message.reply(Type.RESULT, id, "", result);
        }
        tx = new Transaction(id, items, group(items));
        Entry start = new Entry(Role.COORDINATOR, Kind.START_2PC, id, tx.endpoints, tx.items);
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
        active.add(tx);
        server.faults.hit("after-start", id);
        tx.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(server.config.voteTimeoutMs());
        if (tx.endpoints.isEmpty()) {
            decide(tx, ABORTED);
            finish(tx);
            return Message.reply(Type.RESULT, id, "", ABORTED);
        }
        return Message.reply(Type.RESULT, id, "", PENDING);
    }

    Message query(Message request) throws IOException {
        TxId id = request.id();
        require(request.participants().stream().anyMatch(e -> e.id().equals(request.sender())),
                "Decision query sender is outside the participant list");
        Transaction tx = transactions.get(id);
        if (tx == null) {
            server.persist(record(Kind.GLOBAL_ABORT, id));
            tx = tombstone(id);
            transactions.put(id, tx);
        } else if (tx.items != null) {
            require(sameEndpoints(tx.endpoints, request.participants()), "Decision query uses a different participant list");
        }
        return Message.reply(Type.DECISION_REPLY, id, server.node, tx.decision == null ? PENDING : tx.decision);
    }

    void tick(long now) throws IOException {
        if (active.isEmpty()) {
            return;
        }
        for (Transaction tx : new ArrayList<>(active)) {
            if (tx.decision == null && now - tx.deadline >= 0) {
                decide(tx, ABORTED);
            }
            if (tx.decision != null && tx.acks.size() == tx.endpoints.size()) {
                finish(tx);
            } else if (now - tx.nextSend >= 0) {
                tx.nextSend = server.retryAt(now);
                if (tx.decision == null) {
                    prepare(tx);
                } else {
                    deliver(tx);
                }
            }
        }
    }

    private void prepare(Transaction tx) {
        boolean stopAfterFirst = server.faults.matches("after-first-prepare", tx.id);
        for (Endpoint endpoint : tx.endpoints) {
            String peer = endpoint.id();
            if (stopAfterFirst && !tx.prepareCalls.isEmpty()) {
                break;
            }
            if (!tx.yes.contains(peer) && tx.prepareCalls.add(peer)) {
                if (!server.send(endpoint, prepareMessage(tx, peer), reply -> vote(tx, peer, reply))) {
                    tx.prepareCalls.remove(peer);
                }
            }
        }
    }

    private Message prepareMessage(Transaction tx, String peer) {
        return new Message(Type.PREPARE, tx.id, server.node, tx.endpoints, tx.groups.get(peer), "");
    }

    private void vote(Transaction tx, String peer, Message reply) throws IOException {
        tx.prepareCalls.remove(peer);
        if (tx.decision != null || reply == null) {
            return;
        }
        if (reply.type() == Type.VOTE_COMMIT || reply.type() == Type.VOTE_ABORT) {
            server.faults.hit("after-first-prepare", tx.id);
            if (reply.type() == Type.VOTE_ABORT || System.nanoTime() - tx.deadline >= 0) {
                decide(tx, ABORTED);
            } else if (tx.yes.add(peer) && tx.yes.size() == tx.endpoints.size()) {
                server.faults.hit("after-all-yes", tx.id);
                decide(tx, COMMITTED);
            }
        }
    }

    private void deliver(Transaction tx) {
        Type type = COMMITTED.equals(tx.decision) ? Type.GLOBAL_COMMIT : Type.GLOBAL_ABORT;
        boolean stopAfterFirst = server.faults.matches("after-first-decision", tx.id);
        for (Endpoint endpoint : tx.endpoints) {
            String peer = endpoint.id();
            if (stopAfterFirst && !tx.deliveryCalls.isEmpty()) {
                break;
            }
            if (!tx.acks.contains(peer) && tx.deliveryCalls.add(peer)
                    && !server.send(endpoint, Message.simple(type, tx.id, server.node),
                            reply -> acknowledge(tx, peer, reply))) {
                tx.deliveryCalls.remove(peer);
            }
        }
    }

    private void acknowledge(Transaction tx, String peer, Message reply) throws IOException {
        tx.deliveryCalls.remove(peer);
        if (!tx.ended && reply != null && reply.type() == Type.ACK && tx.decision.equals(reply.status())) {
            if (tx.acks.add(peer)) {
                server.faults.hit("after-first-decision", tx.id);
            }
            if (tx.acks.size() == tx.endpoints.size()) {
                finish(tx);
            }
        }
    }

    private void decide(Transaction tx, String decision) throws IOException {
        server.persist(record(COMMITTED.equals(decision) ? Kind.GLOBAL_COMMIT : Kind.GLOBAL_ABORT, tx.id));
        tx.decision = decision;
        tx.nextSend = System.nanoTime();
        server.faults.hit("after-decision", tx.id);
    }

    private void finish(Transaction tx) throws IOException {
        server.persist(record(Kind.END, tx.id));
        tx.ended = true;
        active.remove(tx);
        tx.yes.clear();
        tx.acks.clear();
        server.faults.hit("after-end", tx.id);
    }

    void replay(Entry entry) throws IOException {
        TxId id = entry.id();
        checkReplay(server.node.equals(id.coordinator()), id);
        Transaction tx = transactions.get(id);
        switch (entry.kind()) {
            case START_2PC -> {
                checkReplay(tx == null && new HashSet<>(entry.items()).size() == entry.items().size(), id);
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
        for (Transaction tx : transactions.values()) {
            if (!tx.ended) {
                active.add(tx);
                if (tx.decision == null) {
                    decide(tx, ABORTED);
                }
            }
        }
    }

    private Map<String, List<String>> group(List<String> items) {
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

    static boolean sameEndpoints(List<Endpoint> left, List<Endpoint> right) {
        return left.size() == right.size() && new HashSet<>(left).equals(new HashSet<>(right));
    }

    private final class Transaction {
        final TxId id;
        final List<String> items;
        final Map<String, List<String>> groups;
        final List<Endpoint> endpoints;
        final Set<String> yes = new HashSet<>();
        final Set<String> acks = new HashSet<>();
        final Set<String> prepareCalls = new HashSet<>();
        final Set<String> deliveryCalls = new HashSet<>();
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
