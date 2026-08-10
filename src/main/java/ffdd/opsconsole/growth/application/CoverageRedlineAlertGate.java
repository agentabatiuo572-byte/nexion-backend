package ffdd.opsconsole.growth.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a persistent B1 coverage redline condition visible without turning a
 * scheduler retry into a once-per-minute error storm. It deliberately holds no
 * business state: the lifecycle service is still invoked on every due scan.
 */
final class CoverageRedlineAlertGate {
    private final Set<String> blockedKeys = ConcurrentHashMap.newKeySet();

    boolean firstBlocked(String key) {
        return blockedKeys.add(key);
    }

    boolean clearOnNonBlocked(String key) {
        return blockedKeys.remove(key);
    }
}
