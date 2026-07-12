package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class NovaMapperContractTest {
    @Test
    void withdrawalCollectorRequiresSuccessAndExplicitLegacyCompletedWithCompletionTime() throws Exception {
        Select select = NovaMapper.class
                .getMethod("withdrawalSourceEvents", LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertThat(sql).contains("w.status IN ('SUCCESS', 'COMPLETED')")
                .doesNotContain("UPPER(w.status)")
                .contains("w.completed_at IS NOT NULL")
                .contains("LEGACY_COMPLETED");
    }

    @Test
    void collectorsUseFixedTablesRatherThanDynamicIdentifiers() throws Exception {
        assertThat(selectSql("withdrawalSourceEvents")).contains("FROM nx_withdrawal_order").doesNotContain("${");
        assertThat(selectSql("vrankSourceEvents")).contains("FROM nx_user_level_log").doesNotContain("${");
        assertThat(selectSql("genesisSourceEvents")).contains("FROM nx_genesis_order").doesNotContain("${");
        assertThat(selectSql("newUserSourceEvents")).contains("FROM nx_user").doesNotContain("${");
    }

    @Test
    void hourlyNewUserCollectorIsCompatibleWithOnlyFullGroupBy() throws Exception {
        String sql = selectSql("newUserSourceEvents");

        assertThat(sql)
                .contains("DATE_FORMAT(MIN(u.created_at), '%Y%m%d%H')")
                .contains("DATE_FORMAT(MIN(u.created_at), '%Y-%m-%d %H:00:00')")
                .contains("GROUP BY DATE_FORMAT(u.created_at, '%Y-%m-%d %H:00:00')")
                .contains("HAVING COUNT(1) >= 10")
                .doesNotContain("GROUP BY DATE_FORMAT(u.created_at, '%Y-%m-%d %H:00:00'),");
    }

    @Test
    void genesisCollectorUsesIndexableTerminalTimeBranches() throws Exception {
        String sql = selectSql("genesisSourceEvents");

        assertThat(sql)
                .contains("o.completed_at >= #{since}")
                .contains("o.completed_at IS NULL")
                .contains("o.paid_at >= #{since}")
                .doesNotContain("UPPER(o.status)")
                .doesNotContain("COALESCE(o.completed_at, o.paid_at) >=");
    }

    private String selectSql(String method) throws Exception {
        Select select = NovaMapper.class.getMethod(method, LocalDateTime.class, LocalDateTime.class).getAnnotation(Select.class);
        return String.join(" ", select.value());
    }
}
