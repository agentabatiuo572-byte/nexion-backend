package ffdd.opsconsole.shared.outbox.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class EventOutboxMapperH3RedriveSqlContractTest {
    @Test
    void onlyTheFiveGovernedH3ThresholdEventsCanBeLockedAndRedriven() throws Exception {
        String lock = sql(Select.class, "lockDeadH3ThresholdEvent");
        String redrive = sql(Update.class, "redriveDeadH3ThresholdEvent");
        for (String eventType : java.util.List.of(
                "H3_STOREFRONT_THREE_PRODUCTS_VIEWED",
                "H3_GENESIS_SECONDARY_MARKET_VIEWED",
                "H3_COMPUTE_COMPLETED_50",
                "H3_REFERRAL_REGISTERED",
                "H3_EXCHANGE_COMPLETED")) {
            assertThat(lock).contains(eventType);
            assertThat(redrive).contains(eventType);
        }
        assertThat(lock).contains("status = 'DEAD'", "FOR UPDATE");
        assertThat(redrive).contains("status = 'PENDING'", "next_retry_at = NOW()", "status = 'DEAD'",
                "retry_count = #{expectedRetryCount}");
    }

    @Test
    void redriveSqlLeavesPayloadSourceAndFailureEvidenceUntouched() throws Exception {
        String redrive = sql(Update.class, "redriveDeadH3ThresholdEvent").replaceAll("\\s+", " ").toUpperCase();
        int setStart = redrive.indexOf(" SET ");
        int whereStart = redrive.indexOf(" WHERE ");
        assertThat(setStart).isGreaterThanOrEqualTo(0);
        assertThat(whereStart).isGreaterThan(setStart);
        String assignments = redrive.substring(setStart, whereStart);
        String predicates = redrive.substring(whereStart);

        assertThat(assignments).doesNotContain("PAYLOAD =", "AGGREGATE_TYPE =", "AGGREGATE_ID =", "EVENT_TS =",
                "RETRY_COUNT =", "LAST_ERROR =", "PUBLISHED_AT =");
        assertThat(predicates).contains("RETRY_COUNT = #{EXPECTEDRETRYCOUNT}");
    }

    private String sql(Class<? extends java.lang.annotation.Annotation> annotation, String methodName) throws Exception {
        Method method = java.util.Arrays.stream(EventOutboxMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        if (annotation == Select.class) return String.join("\n", method.getAnnotation(Select.class).value());
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
