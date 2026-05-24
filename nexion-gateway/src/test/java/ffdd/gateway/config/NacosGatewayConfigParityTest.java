package ffdd.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

class NacosGatewayConfigParityTest {
    private static final List<String> ROUTE_FIELDS = List.of("id", "uri");
    private static final List<String> ROUTE_LIST_FIELDS = List.of("predicates[0]", "filters[0]");
    private static final List<String> SHARED_CONFIG_KEYS = List.of(
            "spring.data.redis.host",
            "spring.data.redis.port",
            "spring.data.redis.password",
            "spring.cloud.sentinel.enabled",
            "spring.cloud.sentinel.transport.dashboard",
            "spring.cloud.sentinel.transport.port",
            "nexion.gateway.rate-limit.enabled",
            "nexion.gateway.rate-limit.anonymous-permits-per-minute",
            "nexion.gateway.rate-limit.user-permits-per-minute",
            "nexion.gateway.rate-limit.window-seconds",
            "nexion.gateway.rate-limit.redis.enabled",
            "nexion.gateway.rate-limit.redis.timeout-ms",
            "nexion.gateway.canary.enabled",
            "nexion.gateway.canary.force-header-name",
            "nexion.gateway.canary.force-header-value",
            "nexion.gateway.canary.version-header-name",
            "nexion.gateway.sentinel.enabled",
            "nexion.gateway.sentinel.default-flow-qps",
            "nexion.gateway.sentinel.default-slow-rt-ms",
            "nexion.gateway.sentinel.default-slow-request-amount",
            "nexion.gateway.sentinel.default-degrade-time-window-seconds",
            "nexion.gateway.sentinel.default-stat-interval-ms");

    @Test
    void nacosGatewayConfigMatchesLocalRoutesAndOperationalSettings() {
        Properties local = loadYaml(repoRoot().resolve("nexion-gateway/src/main/resources/application.yml"));
        Properties nacos = loadYaml(repoRoot().resolve("scripts/nacos/nexion-gateway.yaml"));

        int routeCount = countRoutes(local);
        assertThat(routeCount).isPositive();
        assertThat(countRoutes(nacos)).isEqualTo(routeCount);
        for (int index = 0; index < routeCount; index++) {
            assertRouteProperty(nacos, local, index, ROUTE_FIELDS);
            assertRouteProperty(nacos, local, index, ROUTE_LIST_FIELDS);
        }

        for (String key : SHARED_CONFIG_KEYS) {
            assertThat(nacos.getProperty(key))
                    .as("Nacos config key %s should match local application.yml", key)
                    .isEqualTo(local.getProperty(key));
        }

        assertConfigPrefix(nacos, local, "nexion.gateway.sentinel.routes.");
        assertConfigPrefix(nacos, local, "nexion.gateway.canary.routes.");
    }

    private void assertRouteProperty(Properties nacos, Properties local, int index, List<String> fields) {
        for (String field : fields) {
            String key = "spring.cloud.gateway.routes[" + index + "]." + field;
            assertThat(nacos.getProperty(key))
                    .as("Nacos route property %s should match local application.yml", key)
                    .isEqualTo(local.getProperty(key));
        }
    }

    private void assertConfigPrefix(Properties nacos, Properties local, String prefix) {
        local.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> assertThat(nacos.getProperty(key))
                        .as("Nacos config %s should match local application.yml", key)
                        .isEqualTo(local.getProperty(key)));
    }

    private int countRoutes(Properties properties) {
        int count = 0;
        while (properties.containsKey("spring.cloud.gateway.routes[" + count + "].id")) {
            count++;
        }
        return count;
    }

    private Properties loadYaml(Path path) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(path));
        Properties properties = yaml.getObject();
        assertThat(properties).as("YAML should parse: %s", path).isNotNull();
        return properties;
    }

    private Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (cwd.resolve("scripts").toFile().isDirectory()) {
            return cwd;
        }
        return cwd.getParent();
    }
}
