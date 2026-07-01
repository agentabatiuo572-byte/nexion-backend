package ffdd.opsconsole.shared.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsReadTimeSeedPolicy {
    @Value("${nexion.ops.seed.read-time-enabled:false}")
    private final boolean configuredEnabled;
    @Value("false")
    private final boolean directConstructionEnabled;

    public static OpsReadTimeSeedPolicy enabledForDirectConstruction() {
        return new OpsReadTimeSeedPolicy(true, true);
    }

    public static OpsReadTimeSeedPolicy disabledForDirectConstruction() {
        return new OpsReadTimeSeedPolicy(false, true);
    }

    public boolean enabled() {
        return false;
    }
}
