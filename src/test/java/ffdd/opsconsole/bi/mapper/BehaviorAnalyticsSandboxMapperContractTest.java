package ffdd.opsconsole.bi.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsSandboxMapperContractTest {
    @Test
    void productionZeroDeltaEvidenceIsCausallyScopedToTheSandboxActorAndSession() throws Exception {
        String facts = select("productionFactDelta", String.class, String.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        String outbox = select("productionOutboxDelta", String.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class);

        assertThat(facts).contains("actor_hash=#{actorHash}", "session_hash=#{sessionHash}",
                "created_at>=#{from}", "created_at<=#{to}")
                .doesNotContain("source_environment='PRODUCTION'");
        assertThat(outbox).contains("aggregate_type='APP_BEHAVIOR'", "aggregate_id=#{sessionHash}",
                "created_at>=#{from}", "created_at<=#{to}");
    }

    @Test
    void sandboxSessionAuthorityUsesAConnectionScopedMySqlMutex() throws Exception {
        String acquire = select("tryAcquireSessionLock", String.class);
        String release = select("releaseSessionLock", String.class);
        assertThat(acquire).contains("GET_LOCK", "#{lockKey}");
        assertThat(release).contains("RELEASE_LOCK", "#{lockKey}");
    }

    private String select(String name, Class<?>... parameterTypes) throws Exception {
        Method method = BehaviorAnalyticsSandboxMapper.class.getMethod(name, parameterTypes);
        return String.join(" ", method.getAnnotation(Select.class).value());
    }
}
