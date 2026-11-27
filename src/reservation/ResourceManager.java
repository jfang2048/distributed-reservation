package reservation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ResourceManager {
    private final Map<String, TxId> locks = new HashMap<>();
    private final Map<String, TxId> reserved = new HashMap<>();

    boolean claim(TxId id, List<String> items) {
        // The server serializes calls. Check the entire item set before changing any lock
        // so a rejected vote leaves no partial claim.
        for (String item : items) {
            if (reserved.containsKey(item) || (locks.containsKey(item) && !id.equals(locks.get(item)))) {
                return false;
            }
        }
        for (String item : items) {
            locks.put(item, id);
        }
        return true;
    }

    void finish(TxId id, List<String> items, boolean commit) {
        // Commit turns prepared locks into reservations. Release only locks owned by this transaction,
        // since another transaction may own an item after a rejected claim.
        if (commit) {
            for (String item : items) {
                if (!id.equals(locks.get(item))) {
                    throw new IllegalStateException("Missing prepared lock for " + item);
                }
                reserved.put(item, id);
            }
        }
        for (String item : items) {
            locks.remove(item, id);
        }
    }
}
