package ffdd.opsconsole.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Refuse the checked-in development secret in a production deployment. */
@Component
@RequiredArgsConstructor
public class JwtProductionSecretGuard implements InitializingBean {
    static final String DEVELOPMENT_SECRET = "nexion-development-secret-key-change-me-please";

    private final JwtProperties properties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (requiresProductionSecret()
                && (!StringUtils.hasText(properties.getSecret()) || DEVELOPMENT_SECRET.equals(properties.getSecret()))) {
            throw new IllegalStateException("NEXION_JWT_SECRET_REQUIRED_IN_PRODUCTION");
        }
    }

    private boolean requiresProductionSecret() {
        return !UserAuthEnvironment.hasSingleActiveProfile(environment, "dev")
                && !UserAuthEnvironment.hasSingleActiveProfile(environment, "test");
    }
}
