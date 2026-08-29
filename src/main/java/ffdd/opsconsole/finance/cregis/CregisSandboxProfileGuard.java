package ffdd.opsconsole.finance.cregis;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CregisSandboxProfileGuard implements InitializingBean {
    private static final Set<String> ALLOWED_PROFILES = Set.of("test");
    private final CregisProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        Set<String> active = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (properties.getMode() == CregisProperties.Mode.LOCAL_SANDBOX
                && (active.isEmpty() || !ALLOWED_PROFILES.containsAll(active))) {
            throw new IllegalStateException("CREGIS_LOCAL_SANDBOX_PROFILE_FORBIDDEN");
        }
    }
}
