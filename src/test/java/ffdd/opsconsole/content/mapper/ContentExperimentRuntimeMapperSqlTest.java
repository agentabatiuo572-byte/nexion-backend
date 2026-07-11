package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ContentExperimentRuntimeMapperSqlTest {
    @Test
    void conversionInsertRequiresRunningExperimentAndUsesDatabaseUniqueness() throws Exception {
        String sql = String.join("\n", ContentExperimentRuntimeMapper.class
                .getMethod("insertConversionIfAbsent", String.class, long.class, String.class, String.class,
                        LocalDateTime.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql)
                .contains("JOIN nx_content_experiment")
                .contains("JOIN nx_order")
                .contains("o.user_id = a.user_id")
                .contains("o.order_no = #{conversionKey}")
                .contains("payment_status")
                .contains("order_status")
                .contains("state = 'RUNNING'")
                .contains("INSERT IGNORE")
                .doesNotContain("conversion_key =");
    }

    @Test
    void conversionOrderLookupIsBoundToUserAndPaidOrCompletedServerOrder() throws Exception {
        String sql = String.join("\n", ContentExperimentRuntimeMapper.class
                .getMethod("countEligibleConversionOrder", long.class, String.class)
                .getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_order")
                .contains("user_id = #{userId}")
                .contains("order_no = #{orderNo}")
                .contains("is_deleted = 0")
                .contains("'PAID'")
                .contains("'COMPLETED'");
    }

    @Test
    void deliveryLocksRunningExperimentAndMutationsRecheckRunningStateAtomically() throws Exception {
        String lockSql = String.join("\n", ContentExperimentRuntimeMapper.class
                .getMethod("findRunningExperimentForUpdate", String.class)
                .getAnnotation(Select.class).value());
        String assignmentSql = String.join("\n", ContentExperimentRuntimeMapper.class
                .getMethod("insertAssignmentIfAbsent", String.class, long.class, String.class, String.class,
                        int.class, LocalDateTime.class)
                .getAnnotation(Insert.class).value());
        String exposureSql = String.join("\n", ContentExperimentRuntimeMapper.class
                .getMethod("markExposedIfFirst", String.class, long.class, LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class).value());

        assertThat(lockSql).contains("state = 'RUNNING'").contains("FOR UPDATE");
        assertThat(assignmentSql).contains("SELECT").contains("state = 'RUNNING'");
        assertThat(exposureSql).contains("JOIN nx_content_experiment").contains("state = 'RUNNING'");
    }

    @Test
    void variantMetricsAggregateExposedAssignmentsAndSingleUserConversions() throws Exception {
        String sql = String.join("\n", CopyExperimentVariantMapper.class
                .getMethod("listRuntimeMetrics", String.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("exposed_at IS NOT NULL")
                .contains("COUNT(DISTINCT a.user_id)")
                .contains("COUNT(DISTINCT c.user_id)")
                .contains("GROUP BY");
    }
}
