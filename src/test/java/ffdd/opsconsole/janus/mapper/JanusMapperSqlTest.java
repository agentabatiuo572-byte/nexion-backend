package ffdd.opsconsole.janus.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class JanusMapperSqlTest {
    @Test
    void commandAckClearsActivatedWhenTargetIsNotActivated() throws Exception {
        String sql = annotationSql("acknowledgeDeviceCommand", Update.class);
        assertThat(sql).contains("activated=CASE WHEN #{success}=1 THEN desired_status='ACTIVATED'");
    }

    @Test
    void reportEvaluationIsDeduplicatedByTheBusinessReportId() throws Exception {
        String sql = annotationSql("insertEvaluation", Insert.class);
        assertThat(sql).contains("INSERT IGNORE INTO nx_janus_evaluation").contains("report_id");
    }

    @Test
    void adminIdempotencyResultExpiresAfterTwentyFourHours() throws Exception {
        String readSql = annotationSql("findCommand", Select.class);
        String insertSql = annotationSql("insertCommandReservation", Insert.class);
        assertThat(readSql).contains("expires_at>CURRENT_TIMESTAMP(3)");
        assertThat(insertSql).contains("INTERVAL 24 HOUR").contains("'PROCESSING'");
    }

    @Test
    void dailyCapReservationUsesOneAtomicUpsert() throws Exception {
        String sql = annotationSql("reserveDailyEvaluation", Insert.class);
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE")
                .contains("used_count<#{cap}")
                .contains("used_count+LAST_INSERT_ID(0)");
    }

    @Test
    void expiredManualOverrideIsReleasedForSystemEvaluation() throws Exception {
        String sql = annotationSql("expireDeviceOverride", Update.class);
        assertThat(sql).contains("manual_override_json=NULL")
                .doesNotContain("acked_revision>=desired_revision")
                .contains("acked_revision=GREATEST(acked_revision,desired_revision)")
                .contains("'$.expireAt'");
    }

    @Test
    void strategyCommandCannotReplaceAManualOverride() throws Exception {
        String sql = annotationSql("publishStrategyCommand", Update.class);
        assertThat(sql).contains("status_source<>'manual'")
                .contains("desired_revision=desired_revision+1")
                .contains("command_state='FAILED'");
    }

    @Test
    void oldAckReplayIsANoOpAfterANewerRevisionExists() throws Exception {
        String sql = annotationSql("countDeviceCommandAckReplay", Select.class);
        assertThat(sql).contains("acked_revision>=#{revision}").contains("desired_revision>#{revision}");
    }

    private <A extends java.lang.annotation.Annotation> String annotationSql(String methodName, Class<A> type)
            throws Exception {
        Method method = Arrays.stream(JanusMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        A annotation = method.getAnnotation(type);
        String[] values = type == Insert.class ? ((Insert) annotation).value()
                : type == Select.class ? ((Select) annotation).value() : ((Update) annotation).value();
        return String.join(" ", values);
    }
}
