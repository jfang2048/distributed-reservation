package reservation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class Client {
    private record Request(String coordinator, UUID uuid, List<String> items) {}

    private Client() {}

    static void run(ClusterConfig config, String[] args) throws InterruptedException {
        Request request = readRequest(config.defaultCoordinator(), args);
        Endpoint coordinator = config.endpoint(request.coordinator());
        TxId id = new TxId(coordinator.id(), request.uuid());
        Message reserve = new Message(Type.RESERVE, id, "", List.of(), request.items(), "");

        System.out.println("TX_ID " + request.uuid());
        System.out.flush();
        System.out.println(reserve(config, coordinator, reserve));
    }

    private static String reserve(ClusterConfig config, Endpoint coordinator, Message request)
            throws InterruptedException {
        Transport transport = new Transport(config);
        long waitMs = config.voteTimeoutMs() + 2L * config.readTimeoutMs();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        long retryDelay = TimeUnit.MILLISECONDS.toNanos(config.retryDelayMs());
        do {
            Message response;
            try {
                response = transport.call(coordinator, request);
            } catch (IOException failed) {
                System.err.println("Reservation outcome unknown: " + failed.getMessage());
                return "PENDING";
            }
            if (response.type() != Type.RESULT || !request.id().equals(response.id())) {
                throw new IllegalArgumentException("Invalid reservation result from " + coordinator.id());
            }
            if (!response.status().equals("PENDING")) {
                return response.status();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, retryDelay));
            }
        } while (System.nanoTime() - deadline < 0);
        return "PENDING";
    }

    private static Request readRequest(String defaultCoordinator, String[] args) {
        String coordinator = null;
        UUID transaction = null;
        List<String> items = new ArrayList<>();
        Set<String> uniqueItems = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--coordinator" -> {
                    if (coordinator != null) {
                        throw new IllegalArgumentException("Set --coordinator once.");
                    }
                    coordinator = optionValue(args, ++i, arg);
                }
                case "--tx-id" -> {
                    if (transaction != null) {
                        throw new IllegalArgumentException("Set --tx-id once.");
                    }
                    transaction = UUID.fromString(optionValue(args, ++i, arg));
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown reserve option: " + arg);
                    }
                    if (arg.isBlank() || !uniqueItems.add(arg)) {
                        throw new IllegalArgumentException("Provide distinct, nonempty item IDs.");
                    }
                    items.add(arg);
                }
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one item ID.");
        }
        return new Request(coordinator == null ? defaultCoordinator : coordinator,
                transaction == null ? UUID.randomUUID() : transaction, List.copyOf(items));
    }

    private static String optionValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Provide a value for " + option);
        }
        return args[index];
    }
}
