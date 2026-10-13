package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportRuntimeTargetTest {
    @Test void explicitAnalyticsTargetRejectsEveryLegacyResource() {
        var target = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", "analytics-20261007"));
        var legacy = SupportRuntimeTarget.select(Map.of());
        var valid = environment(target);
        assertThatCode(() -> target.requireEnvironment(valid)).doesNotThrowAnyException();
        for (var old : environment(legacy).entrySet()) {
            if (old.getValue().equals(valid.get(old.getKey()))) continue;
            var mixed = new HashMap<>(valid);
            mixed.put(old.getKey(), old.getValue());
            assertThatThrownBy(() -> target.requireEnvironment(mixed)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(target.httpBase()).isEqualTo("http://127.0.0.1:18161");
        assertThat(target.websocketBase()).isEqualTo("ws://127.0.0.1:18161");
    }

    @Test void unknownOrBlankTargetCannotSilentlySelectLegacy() {
        for (String target : new String[] {"", "production", "analytics"})
            assertThatThrownBy(() -> SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", target)))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test void freshCommittedTargetIsFixedAndRejectsOriginalDatabase() {
        var fresh = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", "analytics-committed-r21"));
        var original = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", "analytics-20261007"));
        assertThat(fresh.analytics()).isTrue();
        assertThat(fresh.freshCommitted()).isTrue();
        assertThat(original.analytics()).isTrue();
        assertThat(original.freshCommitted()).isFalse();
        assertThat(SupportRuntimeTarget.select(Map.of()).analytics()).isFalse();
        assertThat(fresh.databasePort()).isEqualTo(33338);
        assertThat(fresh.httpBase()).isEqualTo("http://127.0.0.1:18162");
        assertThat(fresh.websocketBase()).isEqualTo("ws://127.0.0.1:18162");
        assertThatCode(() -> fresh.requireEnvironment(environment(fresh))).doesNotThrowAnyException();
        assertThatThrownBy(() -> fresh.requireEnvironment(environment(original))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> original.requireEnvironment(environment(fresh))).isInstanceOf(IllegalStateException.class);
        var mixed = new HashMap<>(environment(fresh));
        mixed.put("NEXION_REDIS_PORT", "16341");
        assertThatThrownBy(() -> fresh.requireEnvironment(mixed)).isInstanceOf(IllegalStateException.class);
    }

    @Test void netCommittedTargetRequiresRestrictedPrincipalAndFixedCloneResources() {
        var net = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", "analytics-net-committed-r64"));
        assertThat(net.analytics()).isTrue();
        assertThat(net.freshCommitted()).isFalse();
        assertThat(net.netCommitted()).isTrue();
        assertThat(net.database()).isEqualTo("cs_analytics_20261007");
        assertThat(net.databasePort()).isEqualTo(33338);
        assertThat(net.username()).isEqualTo("support_net_r55_app");
        assertThat(net.httpBase()).isEqualTo("http://127.0.0.1:18162");
        assertThat(net.websocketBase()).isEqualTo("ws://127.0.0.1:18162");
        var valid = environment(net);
        assertThatCode(() -> net.requireEnvironment(valid)).doesNotThrowAnyException();
        for (String selector : new String[] {"legacy", "analytics-20261007", "analytics-committed-r21"}) {
            var prior = SupportRuntimeTarget.select(Map.of("SUPPORT_RUNTIME_TARGET", selector));
            assertThat(prior.netCommitted()).isFalse();
            for (var setting : environment(prior).entrySet()) {
                if (setting.getValue().equals(valid.get(setting.getKey()))) continue;
                var mixed = new HashMap<>(valid);
                mixed.put(setting.getKey(), setting.getValue());
                assertThatThrownBy(() -> net.requireEnvironment(mixed)).isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(() -> prior.requireEnvironment(valid)).isInstanceOf(IllegalStateException.class);
        }
    }

    private Map<String, String> environment(SupportRuntimeTarget target) {
        return Map.of("NEXION_DB_URL", target.jdbcPrefix() + "useSSL=false",
                "NEXION_DB_USERNAME", target.username(), "NEXION_REDIS_HOST", "127.0.0.1",
                "NEXION_REDIS_PORT", String.valueOf(target.redisPort()),
                "NEXION_MINIO_ENDPOINT", target.storageEndpoint(), "NEXION_MINIO_BUCKET", target.bucket());
    }
    public static void main(String[] ignored) {
        var probe=new SupportRuntimeTargetTest();
        probe.explicitAnalyticsTargetRejectsEveryLegacyResource();
        probe.unknownOrBlankTargetCannotSilentlySelectLegacy();
        probe.freshCommittedTargetIsFixedAndRejectsOriginalDatabase();
        probe.netCommittedTargetRequiresRestrictedPrincipalAndFixedCloneResources();
        System.out.println("PASS 4 pure target methods; native NOT_EXECUTED");
    }
}
