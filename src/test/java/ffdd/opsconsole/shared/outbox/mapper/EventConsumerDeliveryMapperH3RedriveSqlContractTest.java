package ffdd.opsconsole.shared.outbox.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class EventConsumerDeliveryMapperH3RedriveSqlContractTest {
    @Test
    void onlyTheCanonicalCompletionDeliveryCanBeLockedForH3Redrive() throws Exception {
        String lock = sql(Select.class, "lockDeadH3QuestCompletionDelivery");
        String preview = sql(Select.class, "findDeadH3QuestCompletionDelivery");

        assertThat(lock).contains("consumer_group = 'h3-quest-completion'", "status = 'DEAD'", "FOR UPDATE");
        assertThat(preview).contains("consumer_group = 'h3-quest-completion'", "status = 'DEAD'");
    }

    @Test
    void redrivePreservesAttemptErrorAndOriginalDeliveryEvidenceUnderADeadCountCas() throws Exception {
        String redrive = sql(Update.class, "redriveDeadH3QuestCompletionDelivery")
                .replaceAll("\\s+", " ").toUpperCase();
        int setStart = redrive.indexOf(" SET ");
        int whereStart = redrive.indexOf(" WHERE ");
        assertThat(setStart).isGreaterThanOrEqualTo(0);
        assertThat(whereStart).isGreaterThan(setStart);
        String assignments = redrive.substring(setStart, whereStart);
        String predicates = redrive.substring(whereStart);

        assertThat(assignments).contains("STATUS = 'FAILED'", "NEXT_RETRY_AT = NOW()", "DEAD_AT = NULL")
                .doesNotContain("ATTEMPT_COUNT =", "LAST_ERROR =", "TOPIC =", "MSG_ID =", "EVENT_TYPE =",
                        "AGGREGATE_TYPE =", "AGGREGATE_ID =");
        assertThat(predicates).contains("CONSUMER_GROUP = 'H3-QUEST-COMPLETION'", "STATUS = 'DEAD'",
                "ATTEMPT_COUNT = #{EXPECTEDATTEMPTCOUNT}");
    }

    private String sql(Class<? extends java.lang.annotation.Annotation> annotation, String methodName) throws Exception {
        Method method = java.util.Arrays.stream(EventConsumerDeliveryMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        if (annotation == Select.class) return String.join("\n", method.getAnnotation(Select.class).value());
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
