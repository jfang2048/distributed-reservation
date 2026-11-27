package reservation;

import java.util.Set;
import java.util.concurrent.locks.LockSupport;

final class Faults {
    private static final Set<String> POINTS = Set.of(
            "after-start", "after-first-prepare", "after-all-yes", "after-decision",
            "after-first-decision", "after-end", "before-vote", "after-vote",
            "before-decision-log", "after-apply");

    private final ClusterConfig config;
    private final String node;
    private boolean triggered;

    Faults(ClusterConfig config, String node) {
        this.config = config;
        this.node = node;
        if (config.faultPoint() != null && !POINTS.contains(config.faultPoint())) {
            throw new IllegalArgumentException("Unknown fault point: " + config.faultPoint());
        }
    }

    boolean matches(String point, TxId id) {
        return !triggered && node.equals(config.faultNode()) && point.equals(config.faultPoint())
                && (config.faultTxId() == null || config.faultTxId().equals(id.uuid()));
    }

    void hit(String point, TxId id) {
        if (matches(point, id)) {
            // Flush the marker before halting without cleanup. In pause mode, stay at the fault
            // boundary even if the thread is interrupted, until the operator stops the process.
            triggered = true;
            System.out.println("FAULT " + point + " " + id.uuid());
            System.out.flush();
            if ("halt".equals(config.faultAction())) {
                Runtime.getRuntime().halt(91);
            }
            while (true) {
                LockSupport.park();
                Thread.interrupted();
            }
        }
    }
}
