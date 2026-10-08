package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SupportDeviceReadMapperSqlTest {
    @Test void currentReadUsesExactScopedOrdinaryProjectionAndPreservesHoldingEvidence() throws Exception {
        var configuration = new Configuration();
        configuration.addMapper(SupportDeviceReadMapper.class);
        var statement = configuration.getMappedStatement(SupportDeviceReadMapper.class.getName() + ".readCurrent");
        var bound = statement.getBoundSql(Map.of("customerIds", List.of(7L, 9L)));
        var sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("d.user_id IN ( ? , ? )", "d.is_deleted=0",
            "LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0",
            "d.ownership_status ownershipStatus", "d.deactivated_at deactivatedAt", "d.pending_deactivate pendingDeactivate",
            "d.source_order_no sourceOrderNo", "d.source_channel sourceChannel", "d.device_type deviceType", "d.hashrate",
            "r.paused_reason pausedReason", "r.active_task_no activeTaskNo", "r.heartbeat_at heartbeatAt",
            "NOW(6) evaluatedDbAt", "ORDER BY d.id")
            .doesNotContain("FOR UPDATE", "FOR SHARE", "${", "nx_order", "nx_user_wallet", "r.is_deleted=0 WHERE r.",
                "d.deactivated_at IS NULL", "d.pending_deactivate=0", "d.source_channel=", "d.device_type<>");
        assertThat(bound.getParameterMappings()).hasSize(2);
        for (var parameter : bound.getParameterMappings())
            assertThat(bound.getAdditionalParameter(parameter.getProperty())).isIn(7L, 9L);
        var options = SupportDeviceReadMapper.class.getMethod("readCurrent", List.class).getAnnotation(Options.class);
        assertThat(options.useCache()).isFalse();
        assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
    }

    @Test void heartbeatWindowReusesTheRealInclusiveTenMinuteDatabasePredicate() {
        var configuration = new Configuration();
        configuration.addMapper(SupportDeviceReadMapper.class);
        var sql = configuration.getMappedStatement(SupportDeviceReadMapper.class.getName() + ".readCurrent")
            .getBoundSql(Map.of("customerIds", List.of(7L))).getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("CASE WHEN" + DeviceOpsMapper.E5_HEARTBEAT_FRESH + " THEN 1 ELSE 0 END heartbeatFresh",
            "BETWEEN DATE_SUB(NOW(6), INTERVAL 10 MINUTE) AND NOW(6)");
    }
}
