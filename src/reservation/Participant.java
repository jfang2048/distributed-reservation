package reservation;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import reservation.TransactionLog.Entry;
import reservation.TransactionLog.Kind;
import reservation.TransactionLog.Role;

import static reservation.Server.*;

final class Participant {
    private final Server server;
    private final ResourceManager resources = new ResourceManager();
    private final Map<TxId, Transaction> transactions = new HashMap<>();
    private final Set<Transaction> ready = new LinkedHashSet<>();

    Participant(Server server) {
        this.server = server;
    }

    Message prepare(Message request) throws IOException {
        TxId id = request.id();
        require(request.sender().equals(id.coordinator()), "Prepare must come from the coordinator");
        require(includes(request.participants(), server.node) && owns(request.items()), "Invalid prepare items");
        Transaction tx = transactions.get(id);
        if (tx == null) {
            tx = new Transaction(id, request.participants(), request.items());
            server.persist(entry(Kind.INIT, tx));
            transactions.put(id, tx);
        } else if (!tx.endpoints.isEmpty()) {
            require(Coordinator.sameEndpoints(tx.endpoints, request.participants())
                    && (tx.items.isEmpty() || tx.items.equals(request.items())),
                    "Prepare changed the participant list or items");
        }
        if (tx.vote == null && tx.decision == null) {
            boolean yes = resources.claim(id, tx.items);
            server.faults.hit("before-vote", id);
            recordVote(tx, yes);
            server.faults.hit("after-vote", id);
        }
        return Message.simple(Boolean.TRUE.equals(tx.vote) ? Type.VOTE_COMMIT : Type.VOTE_ABORT, id, server.node);
    }

    Message decide(Message request) throws IOException {
        TxId id = request.id();
        require(request.sender().equals(id.coordinator()), "Decision must come from the coordinator");
        boolean commit = request.type() == Type.GLOBAL_COMMIT;
        Transaction tx = transactions.get(id);
        require(!commit || (tx != null && Boolean.TRUE.equals(tx.vote)), "Commit requires a saved yes vote");
        if (tx == null) {
            tx = new Transaction(id, List.of(), List.of());
            transactions.put(id, tx);
        }
        apply(tx, commit ? COMMITTED : ABORTED);
        return Message.reply(Type.ACK, id, server.node, tx.decision);
    }

    Message query(Message request) throws IOException {
        TxId id = request.id();
        require(includes(request.participants(), server.node) && includes(request.participants(), request.sender()),
                "Decision query requires both participants");
        Transaction tx = transactions.get(id);
        if (tx == null) {
            tx = new Transaction(id, request.participants(), List.of());
            transactions.put(id, tx);
        } else if (!tx.endpoints.isEmpty()) {
            require(Coordinator.sameEndpoints(tx.endpoints, request.participants()), "Decision query uses a different participant list");
        }
        if (tx.vote == null && tx.decision == null) {
            recordVote(tx, false);
        }
        String result = tx.decision != null ? tx.decision : Boolean.FALSE.equals(tx.vote) ? NO : PENDING;
        return Message.reply(Type.DECISION_REPLY, id, server.node, result);
    }

    void tick(long now) {
        for (Transaction tx : ready) {
            if (now - tx.nextQuery < 0) {
                continue;
            }
            tx.nextQuery = server.retryAt(now);
            Set<Endpoint> targets = new LinkedHashSet<>();
            targets.add(server.config.endpoint(tx.id.coordinator()));
            for (Endpoint endpoint : tx.endpoints) {
                if (!server.node.equals(endpoint.id())) {
                    targets.add(endpoint);
                }
            }
            for (Endpoint endpoint : targets) {
                if (tx.queries.add(endpoint.id())) {
                    Message query = new Message(Type.DECISION_REQUEST, tx.id, server.node,
                            tx.endpoints, List.of(), "");
                    if (!server.send(endpoint, query, reply -> learn(tx, endpoint, reply))) {
                        tx.queries.remove(endpoint.id());
                    }
                }
            }
        }
    }

    private void learn(Transaction tx, Endpoint endpoint, Message reply) throws IOException {
        tx.queries.remove(endpoint.id());
        if (tx.decision == null && reply != null && reply.type() == Type.DECISION_REPLY) {
            switch (reply.status()) {
                case COMMITTED -> apply(tx, COMMITTED);
                case ABORTED, NO -> apply(tx, ABORTED);
                default -> { }
            }
        }
    }

    private void recordVote(Transaction tx, boolean yes) throws IOException {
        server.persist(entry(yes ? Kind.VOTE_COMMIT : Kind.VOTE_ABORT, tx));
        tx.vote = yes;
        if (yes) {
            addReady(tx);
        }
    }

    private void addReady(Transaction tx) {
        tx.nextQuery = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(server.config.voteTimeoutMs());
        ready.add(tx);
    }

    private void apply(Transaction tx, String outcome) throws IOException {
        if (tx.decision != null) {
            require(tx.decision.equals(outcome), "Received conflicting decisions");
            return;
        }
        server.faults.hit("before-decision-log", tx.id);
        boolean commit = COMMITTED.equals(outcome);
        server.persist(new Entry(Role.PARTICIPANT, commit ? Kind.GLOBAL_COMMIT : Kind.GLOBAL_ABORT,
                tx.id, List.of(), tx.items));
        resources.finish(tx.id, tx.items, commit);
        tx.decision = outcome;
        ready.remove(tx);
        server.faults.hit("after-apply", tx.id);
    }

    void replay(Entry entry) throws IOException {
        TxId id = entry.id();
        Transaction tx = transactions.get(id);
        switch (entry.kind()) {
            case INIT -> {
                validateStoredEndpoints(entry);
                checkReplay(tx == null && owns(entry.items()), id);
                transactions.put(id, new Transaction(id, entry.participants(), entry.items()));
            }
            case VOTE_COMMIT, VOTE_ABORT -> {
                validateStoredEndpoints(entry);
                boolean yes = entry.kind() == Kind.VOTE_COMMIT;
                if (tx == null) {
                    checkReplay(!yes && entry.items().isEmpty(), id);
                    tx = new Transaction(id, entry.participants(), entry.items());
                    transactions.put(id, tx);
                }
                checkReplay(tx.vote == null && tx.decision == null && tx.items.equals(entry.items())
                        && Coordinator.sameEndpoints(tx.endpoints, entry.participants()), id);
                tx.vote = yes;
                if (yes) {
                    checkReplay(resources.claim(id, tx.items), id);
                    addReady(tx);
                }
            }
            case GLOBAL_COMMIT, GLOBAL_ABORT -> {
                boolean commit = entry.kind() == Kind.GLOBAL_COMMIT;
                if (tx == null) {
                    checkReplay(!commit && entry.items().isEmpty(), id);
                    tx = new Transaction(id, List.of(), List.of());
                    transactions.put(id, tx);
                }
                checkReplay(tx.decision == null && tx.items.equals(entry.items())
                        && (!commit || Boolean.TRUE.equals(tx.vote)), id);
                resources.finish(id, tx.items, commit);
                tx.decision = commit ? COMMITTED : ABORTED;
                ready.remove(tx);
            }
            default -> checkReplay(false, id);
        }
    }

    private void validateStoredEndpoints(Entry entry) throws IOException {
        try {
            server.config.endpoint(entry.id().coordinator());
            server.validateEndpoints(entry.participants());
            checkReplay(includes(entry.participants(), server.node), entry.id());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid participants in transaction log for " + entry.id(), invalid);
        }
    }

    private boolean owns(List<String> items) {
        return !items.isEmpty() && new HashSet<>(items).size() == items.size()
                && items.stream().allMatch(item -> server.node.equals(server.config.owner(item)));
    }

    private static boolean includes(List<Endpoint> endpoints, String node) {
        return endpoints.stream().anyMatch(endpoint -> endpoint.id().equals(node));
    }

    private static Entry entry(Kind kind, Transaction tx) {
        return new Entry(Role.PARTICIPANT, kind, tx.id, tx.endpoints, tx.items);
    }

    private static final class Transaction {
        final TxId id;
        final List<Endpoint> endpoints;
        final List<String> items;
        final Set<String> queries = new HashSet<>();
        long nextQuery;
        Boolean vote;
        String decision;

        Transaction(TxId id, List<Endpoint> endpoints, List<String> items) {
            this.id = id;
            this.endpoints = endpoints;
            this.items = items;
        }
    }
}
