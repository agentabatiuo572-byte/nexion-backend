package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceOpsMapperSqlTest {
    @Test
    void deviceProjectionUsesExecutableLessThanOrEqualOperator() {
        assertThat(DeviceOpsMapper.DEVICE_COLUMNS)
                .contains("NOT s.id > d.id")
                .doesNotContain("&lt;=");
    }

    @Test
    void emptyTradeinFleetReturnsZeroCliffDevicesInsteadOfNull() throws Exception {
        var query = DeviceOpsMapper.class
                .getDeclaredMethod("tradeinOverviewMetrics", int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);

        assertThat(String.join(" ", query.value()))
                .contains("COALESCE(SUM(")
                .contains("0) AS cliffDeviceCount");
    }

    @Test
    void e5BatchCandidatesAreLockedAndPauseSkipsAlreadyPausedDevices() throws Exception {
        var candidateQuery = DeviceOpsMapper.class
                .getDeclaredMethod("lockE5BatchCandidateIds", Long.class, int.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var pauseQuery = DeviceOpsMapper.class
                .getDeclaredMethod("pauseDevicesByUser", Long.class, String.class, java.time.LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class);

        assertThat(String.join(" ", candidateQuery.value()))
                .contains("r.paused_reason IS NULL", "r.paused_reason IS NOT NULL", "FOR UPDATE");
        assertThat(String.join(" ", pauseQuery.value()))
                .contains("r.paused_reason IS NULL", "is_deleted = 0");
    }
}
