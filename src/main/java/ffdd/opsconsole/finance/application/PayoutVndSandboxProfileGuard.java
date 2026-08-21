package ffdd.opsconsole.finance.application;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PayoutVndSandboxProfileGuard implements InitializingBean {
    private static final Set<String> ALLOWED_PROFILES = Set.of("dev", "test");

    private final PayoutVndProviderProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        String[] active = environment.getActiveProfiles();
        if (properties.getMode() == PayoutVndProviderProperties.Mode.LOCAL_SANDBOX
                && (active.length != 1 || !ALLOWED_PROFILES.contains(active[0]))) {
            throw new IllegalStateException("PAYOUT_VND_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }
}
