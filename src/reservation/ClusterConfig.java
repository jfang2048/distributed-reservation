package reservation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class ClusterConfig {
    private record Node(Endpoint endpoint, String bind, int listenPort, Path logDir) {}

    private final Map<String, Node> nodes;
    private final Map<String, String> owners;
    private final String defaultCoordinator;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int voteTimeoutMs;
    private final int retryDelayMs;
    private final String faultNode;
    private final String faultPoint;
    private final String faultAction;
    private final UUID faultTxId;

    private ClusterConfig(Properties properties) {
        nodes = readNodes(properties);
        owners = readOwners(properties);
        defaultCoordinator = required(properties, "client.coordinator");
        node(defaultCoordinator);

        connectTimeoutMs = number(properties, "tcp.connectTimeoutMs", "2000", Integer.MAX_VALUE);
        readTimeoutMs = number(properties, "tcp.readTimeoutMs", "2000", Integer.MAX_VALUE);
        voteTimeoutMs = number(properties, "tx.voteTimeoutMs", "5000", Integer.MAX_VALUE);
        retryDelayMs = number(properties, "tx.retryDelayMs", "500", Integer.MAX_VALUE);

        faultPoint = properties.getProperty("fault.point");
        faultNode = properties.getProperty("fault.node");
        faultAction = properties.getProperty("fault.action", "halt").strip();
        String faultId = properties.getProperty("fault.txId");
        faultTxId = faultId == null ? null : UUID.fromString(faultId.strip());
        if (faultPoint != null && (faultPoint.isBlank() || faultNode == null)) {
            throw new IllegalArgumentException("Set fault.point and fault.node together.");
        }
        if (faultNode != null) {
            node(faultNode.strip());
        }
        if (!faultAction.equals("halt") && !faultAction.equals("pause")) {
            throw new IllegalArgumentException("fault.action must be halt or pause.");
        }
    }

    static ClusterConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return new ClusterConfig(properties);
    }

    private static Map<String, Node> readNodes(Properties properties) {
        Map<String, Node> result = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("node.") || !key.endsWith(".host")) {
                continue;
            }
            if (key.length() <= "node..host".length()) {
                throw new IllegalArgumentException("Missing node ID: " + key);
            }
            String id = key.substring("node.".length(), key.length() - ".host".length());
            if (!id.matches("[a-zA-Z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid node ID: " + id);
            }
            String prefix = "node." + id + ".";
            // Peers dial the advertised endpoint. The local bind address and listen port can differ,
            // for example when a forwarded port is used.
            int port = number(properties, prefix + "port", null, 65535);
            int listenPort = number(properties, prefix + "listenPort", Integer.toString(port), 65535);
            String bind = properties.getProperty(prefix + "bind", "0.0.0.0").strip();
            String logDir = properties.getProperty(prefix + "logDir", "run/" + id).strip();
            if (bind.isEmpty() || logDir.isEmpty()) {
                throw new IllegalArgumentException("Empty bind address or log directory for node " + id);
            }
            result.put(id, new Node(new Endpoint(id, required(properties, key), port),
                    bind, listenPort, Path.of(logDir)));
        }
        return Map.copyOf(result);
    }

    private Map<String, String> readOwners(Properties properties) {
        Map<String, String> result = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("resource.") && key.endsWith(".owner")) {
                if (key.length() <= "resource..owner".length()) {
                    throw new IllegalArgumentException("Missing item ID: " + key);
                }
                String item = key.substring("resource.".length(), key.length() - ".owner".length());
                if (item.isBlank()) {
                    throw new IllegalArgumentException("Missing item ID: " + key);
                }
                String owner = required(properties, key);
                node(owner);
                result.put(item, owner);
            }
        }
        return Map.copyOf(result);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing config setting: " + key);
        }
        return value.strip();
    }

    private static int number(Properties properties, String key, String fallback, int max) {
        String value = fallback == null ? required(properties, key) : properties.getProperty(key, fallback);
        try {
            int number = Integer.parseInt(value.strip());
            if (number >= 1 && number <= max) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // Report the property name and range for any invalid value.
        }
        throw new IllegalArgumentException(key + " must be an integer from 1 to " + max);
    }

    private Node node(String id) {
        Node node = id == null ? null : nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + id);
        }
        return node;
    }

    Endpoint endpoint(String id) { return node(id).endpoint(); }
    String owner(String item) { return owners.get(item); }
    String bind(String id) { return node(id).bind(); }
    int listenPort(String id) { return node(id).listenPort(); }
    Path logDir(String id) { return node(id).logDir(); }
    String defaultCoordinator() { return defaultCoordinator; }
    int connectTimeoutMs() { return connectTimeoutMs; }
    int readTimeoutMs() { return readTimeoutMs; }
    int voteTimeoutMs() { return voteTimeoutMs; }
    int retryDelayMs() { return retryDelayMs; }
    String faultNode() { return faultNode == null ? null : faultNode.strip(); }
    String faultPoint() { return faultPoint == null ? null : faultPoint.strip(); }
    String faultAction() { return faultAction; }
    UUID faultTxId() { return faultTxId; }
}
