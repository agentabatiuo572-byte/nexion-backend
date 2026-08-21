package ffdd.opsconsole.device.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/**
 * Guards projections that have no run-scoped device/task source yet.
 *
 * <p>Sandbox, unknown and mixed profiles must fail before the caller reads an
 * identity or touches an idempotency/locking path.  An empty profile is the
 * deploy-time production default; an explicitly active profile is production
 * only when it is exactly {@code production} or {@code default}.</p>
 */
final class ProductionDeviceRuntimeGate {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod");

    private ProductionDeviceRuntimeGate() { }

    static void requireProduction(Environment environment, String errorCode) {
        String[] activeProfiles = environment == null ? new String[0] : environment.getActiveProfiles();
        Set<String> profiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        if (!(profiles.isEmpty() || profiles.size() == 1
                && PRODUCTION_PROFILES.contains(profiles.iterator().next()))) {
            throw new BizException(503, errorCode);
        }
    }
}
