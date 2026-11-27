package reservation;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import reservation.TransactionLog.Entry;

final class Server {
    static final String COMMITTED = "COMMITTED";
    static final String ABORTED = "ABORTED";
    static final String PENDING = "PENDING";
    static final String NO = "VOTE_ABORT";

    final ClusterConfig config;
    final String node;
    final Faults faults;
    private final Transport transport;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor outgoing = new ThreadPoolExecutor(
            8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128));
    private final Coordinator coordinator;
    private final Participant participant;
    private TransactionLog log;

    Server(ClusterConfig config, String node) {
        this.config = config;
        this.node = node;
        config.endpoint(node);
        faults = new Faults(config, node);
        transport = new Transport(config);
        coordinator = new Coordinator(this);
        participant = new Participant(this);
    }

    void run() throws IOException {
        try (var transactionLog = new TransactionLog(config.logDir(node))) {
            log = transactionLog;
            // Restore both roles and resolve undecided coordinator transactions before ticks or requests can run.
            for (Entry entry : log.replay()) {
                switch (entry.role()) {
                    case COORDINATOR -> coordinator.replay(entry);
                    case PARTICIPANT -> participant.replay(entry);
                }
            }
            coordinator.recover();
            int tickMs = Math.min(50, config.retryDelayMs());
            timer.scheduleWithFixedDelay(this::tick, 0, tickMs, TimeUnit.MILLISECONDS);
            try {
                transport.serve(node, this::handle);
            } finally {
                timer.shutdownNow();
                outgoing.shutdownNow();
                synchronized (this) {
                    log = null;
                }
            }
        }
    }

    // Ticks, request handlers, and reply callbacks share this monitor so role state and resource locks
    // cannot change concurrently on this node.
    private synchronized void tick() {
        if (log == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            coordinator.tick(now);
            participant.tick(now);
        } catch (IOException | RuntimeException error) {
            fatal(error);
        }
    }

    private synchronized Message handle(Message request) {
        if (log == null) {
            return null;
        }
        try {
            config.endpoint(request.id().coordinator());
            if (!request.sender().isEmpty()) {
                config.endpoint(request.sender());
            }
            validateEndpoints(request.participants());
            return switch (request.type()) {
                case RESERVE -> coordinator.reserve(request);
                case PREPARE, GLOBAL_COMMIT, GLOBAL_ABORT -> {
                    require(request.sender().equals(request.id().coordinator()), "Expected coordinator as sender");
                    yield request.type() == Type.PREPARE ? participant.prepare(request) : participant.decide(request);
                }
                case DECISION_REQUEST -> node.equals(request.id().coordinator())
                        ? coordinator.query(request) : participant.query(request);
                case VOTE_COMMIT, VOTE_ABORT, ACK, RESULT, DECISION_REPLY -> null;
            };
        } catch (IllegalArgumentException invalidRequest) {
            System.err.println("Rejected request: " + invalidRequest.getMessage());
            return null;
        } catch (IOException | RuntimeException error) {
            fatal(error);
            return null;
        }
    }

    void validateEndpoints(List<Endpoint> endpoints) {
        for (Endpoint endpoint : endpoints) {
            require(config.endpoint(endpoint.id()).equals(endpoint),
                    "Invalid participant endpoint: " + endpoint.id());
        }
    }

    static boolean includes(List<Endpoint> endpoints, String node) {
        return endpoints.stream().anyMatch(endpoint -> endpoint.id().equals(node));
    }

    static boolean sameEndpoints(List<Endpoint> left, List<Endpoint> right) {
        return left.size() == right.size() && new HashSet<>(left).containsAll(right);
    }

    void persist(Entry entry) throws IOException {
        log.append(entry);
        System.out.printf("%s %s %s %s%n", node, entry.id().uuid(), entry.role(), entry.kind());
    }

    boolean send(Endpoint endpoint, Message request, Response consumer) {
        // Run socket I/O outside the server monitor. The coordinator can also be a local participant,
        // in which case the worker dispatches through the same request handler without opening a socket.
        try {
            outgoing.execute(() -> {
                Message reply = null;
                try {
                    reply = node.equals(endpoint.id()) ? handle(request) : transport.call(endpoint, request);
                } catch (IOException ignored) {
                    // The role retries or lets its deadline expire.
                } catch (RuntimeException error) {
                    fatal(error);
                }
                if (reply != null && (!request.id().equals(reply.id()) || !endpoint.id().equals(reply.sender()))) {
                    reply = null;
                }
                // Apply the reply under the state monitor, provided shutdown has not detached the log.
                synchronized (this) {
                    if (log != null) {
                        try {
                            consumer.accept(reply);
                        } catch (IOException | RuntimeException error) {
                            fatal(error);
                        }
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    long retryAt(long now) {
        return now + TimeUnit.MILLISECONDS.toNanos(config.retryDelayMs());
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static void checkReplay(boolean condition, TxId id) throws IOException {
        if (!condition) {
            throw new IOException("Inconsistent transaction log for " + id);
        }
    }

    private static void fatal(Exception error) {
        System.err.println("Stopping server: " + error);
        Runtime.getRuntime().halt(2);
    }

    @FunctionalInterface
    interface Response {
        void accept(Message reply) throws IOException;
    }
}
