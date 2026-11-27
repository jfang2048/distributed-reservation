package reservation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ResourceManager {
    private final Map<String, TxId> locks = new HashMap<>();
    private final Map<String, TxId> reserved = new HashMap<>();

    boolean claim(TxId id, List<String> items) {
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
