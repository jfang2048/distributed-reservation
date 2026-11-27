package reservation;

import java.util.Objects;

record Endpoint(String id, String host, int port) {
    Endpoint {
        Objects.requireNonNull(id);
        Objects.requireNonNull(host);
        if (id.isBlank() || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid endpoint");
        }
    }
}
