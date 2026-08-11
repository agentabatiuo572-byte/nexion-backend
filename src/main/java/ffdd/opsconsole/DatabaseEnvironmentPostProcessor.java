package ffdd.opsconsole;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class DatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "nexionAuthoritativeDatabaseEnvironment";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyAuthoritativePropertySource(environment, System.getenv());
    }

    void applyAuthoritativePropertySource(ConfigurableEnvironment environment, Map<String, String> processEnvironment) {
        DatabaseEnvironmentResolver.ResolvedDatabase resolved = DatabaseEnvironmentResolver.resolve(processEnvironment);
        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "spring.datasource.url", resolved.jdbcUrl(),
                "spring.datasource.username", resolved.username(),
                "spring.datasource.password", resolved.password()));
        environment.getPropertySources().addFirst(propertySource);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
