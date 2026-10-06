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

    private Map<String, String> environment(SupportRuntimeTarget target) {
        return Map.of("NEXION_DB_URL", target.jdbcPrefix() + "useSSL=false",
                "NEXION_DB_USERNAME", target.username(), "NEXION_REDIS_HOST", "127.0.0.1",
                "NEXION_REDIS_PORT", String.valueOf(target.redisPort()),
                "NEXION_MINIO_ENDPOINT", target.storageEndpoint(), "NEXION_MINIO_BUCKET", target.bucket());
    }
}
