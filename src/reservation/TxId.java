package reservation;

import java.util.Objects;
import java.util.UUID;

record TxId(String coordinator, UUID uuid) {
    TxId {
        Objects.requireNonNull(coordinator);
        Objects.requireNonNull(uuid);
        if (coordinator.isBlank()) {
            throw new IllegalArgumentException("Transaction needs a coordinator");
        }
    }
}
