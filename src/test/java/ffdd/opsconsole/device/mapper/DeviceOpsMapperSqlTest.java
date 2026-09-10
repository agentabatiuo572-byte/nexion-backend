package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class DeviceOpsMapperSqlTest {
    @Test
    void e5SlotProjectionReleasesPendingDeactivationsLikeCanonicalCapacity() {
        assertThat(DeviceOpsMapper.DEVICE_COLUMNS)
                .contains("a.pending_deactivate = 0", "s.pending_deactivate = 0", "d.pending_deactivate = 1",
                        "a.activated_at IS NOT NULL", "s.activated_at IS NOT NULL", "d.activated_at IS NULL",
                        "a.ownership_status = 'OWNED'", "s.ownership_status = 'OWNED'",
                        "a.device_type,''),'DEVICE')) != 'SHARE'", "s.device_type,''),'DEVICE')) != 'SHARE'");
    }

    @Test
    void e5RuntimeEligibilityKeepsCloudShareWhilePhysicalSlotCountsExcludeIt() throws Exception {
        String physicalCount = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("countActiveDevicesByUser", Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String runtimeOnline = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("countOnlineDevices")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());

        assertThat(DeviceOpsMapper.E5_ACTIVATED_OWNED)
                .contains("d.activated_at IS NOT NULL", "d.deactivated_at IS NULL", "d.pending_deactivate = 0",
                        "UPPER(d.ownership_status) = 'OWNED'", "'OFFLINE'")
                .doesNotContain("'SHARE'");
        assertThat(DeviceOpsMapper.E5_PHYSICAL_SLOT).contains("<> 'SHARE'");
        assertThat(physicalCount).contains("d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                "d.activated_at IS NOT NULL", "d.pending_deactivate = 0", "UPPER(d.ownership_status) = 'OWNED'", "<> 'SHARE'");
        assertThat(runtimeOnline).contains("d.activated_at IS NOT NULL", "UPPER(d.ownership_status) = 'OWNED'")
                .doesNotContain("<> 'SHARE'");
    }
    @Test
    void deviceProjectionUsesExecutableLessThanOrEqualOperator() {
        assertThat(DeviceOpsMapper.DEVICE_COLUMNS)
                .contains("NOT s.id > d.id")
                .doesNotContain("<>")
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
    void pauseAndDeactivateUseRuntimeFactsAndIncludeEveryDisplayedActiveLifecycle() throws Exception {
        String candidates = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("lockE5BatchCandidateIds", Long.class, int.class, int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String userPause = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("pauseDevicesByUser", Long.class, String.class, java.time.LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class).value());
        String datacenterPause = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("pauseRuntimeByDatacenter", String.class, String.class, java.time.LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Insert.class).value());
        String deactivate = String.join(" ", DeviceOpsMapper.class
                .getDeclaredMethod("deactivateE5Device", Long.class, int.class, java.time.LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class).value());

        assertThat(candidates).contains("d.status IN ('ONLINE','BUSY','RUNNING','OFFLINE','ACTIVE')",
                "r.paused_reason IS NULL", "r.paused_reason IS NOT NULL", "FOR UPDATE");
        assertThat(userPause).contains("SELECT d.id, 'UNKNOWN'",
                "d.status IN ('ONLINE','BUSY','RUNNING','OFFLINE','ACTIVE')", "r.paused_reason IS NULL")
                .doesNotContain("COALESCE(NULLIF(d.status,''),'OFFLINE')", "online_status = VALUES(online_status)");
        assertThat(datacenterPause).contains("SELECT d.id, 'UNKNOWN'", "ON DUPLICATE KEY UPDATE paused_reason")
                .doesNotContain("SELECT d.id, d.status", "online_status = VALUES(online_status)");
        assertThat(deactivate).contains("status IN ('ONLINE','BUSY','RUNNING','OFFLINE','ACTIVE')",
                "UPPER(t.status) IN ('CLAIMED','RUNNING')", "pending_deactivate = CASE");
    }
    @Test
    void pendingDeactivateFilterUsesTheDeferredFlagForCountAndPage() throws Exception {
        var countQuery = DeviceOpsMapper.class
                .getDeclaredMethod("countDevices", String.class, String.class, String.class,
                        Long.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        var pageQuery = DeviceOpsMapper.class
                .getDeclaredMethod("pageDevices", String.class, String.class, String.class,
                        Long.class, String.class, String.class, long.class, long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);

        assertThat(String.join(" ", countQuery.value()))
                .contains("status == \"PENDING-DEACTIVATE\"", "d.pending_deactivate = 1");
        assertThat(String.join(" ", pageQuery.value()))
                .contains("status == \"PENDING-DEACTIVATE\"", "d.pending_deactivate = 1");
    }

    @Test
    void runtimeAvailabilityOwnsOnlineOfflineAndUnknownFiltersForBothCountAndPage() throws Exception {
        for (String methodName : java.util.List.of("countDevices", "pageDevices")) {
            var method = "countDevices".equals(methodName)
                    ? DeviceOpsMapper.class.getDeclaredMethod(methodName, String.class, String.class, String.class,
                            Long.class, String.class, String.class)
                    : DeviceOpsMapper.class.getDeclaredMethod(methodName, String.class, String.class, String.class,
                            Long.class, String.class, String.class, long.class, long.class);
            String sql = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Select.class).value());
            assertThat(sql).contains(
                    "status == \"ACTIVE\"", "status == \"ONLINE\"", "status == \"BUSY\"", "status == \"OFFLINE\"", "status == \"UNKNOWN\"",
                    "UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'",
                    "UPPER(TRIM(COALESCE(r.online_status, ''))) = 'OFFLINE'",
                    "UPPER(TRIM(COALESCE(r.online_status, ''))) NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')",
                    "d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                    "d.activated_at IS NOT NULL", "d.deactivated_at IS NULL", "d.pending_deactivate = 0",
                    "UPPER(d.ownership_status) = 'OWNED'");
        }
    }

    @Test
    void runtimeStateBranchesRenderEquivalentCountAndPagePredicates() throws Exception {
        for (String status : java.util.List.of("ACTIVE", "BUSY", "OFFLINE", "UNKNOWN")) {
            String count = renderDeviceQuery("countDevices", status).getSql();
            String page = renderDeviceQuery("pageDevices", status).getSql();
            for (String sql : java.util.List.of(count, page)) {
                if ("UNKNOWN".equals(status)) {
                    assertThat(sql).contains("d.pending_deactivate = 0",
                            "UPPER(COALESCE(d.status, '')) NOT IN",
                            "'RECYCLED','DEACTIVATED','RETIRED','UNBOUND','INVENTORY','PENDING','PENDING_ACTIVATION','INACTIVE'");
                } else {
                    assertThat(sql).contains("d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                            "d.activated_at IS NOT NULL", "d.deactivated_at IS NULL", "d.pending_deactivate = 0",
                            "UPPER(d.ownership_status) = 'OWNED'");
                }
            }
            if ("OFFLINE".equals(status)) {
                assertThat(count).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'OFFLINE'");
                assertThat(page).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'OFFLINE'");
            } else if ("UNKNOWN".equals(status)) {
                assertThat(count).contains("NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')");
                assertThat(page).contains("NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')");
            } else {
                assertThat(count).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'");
                assertThat(page).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'");
            }
            if ("BUSY".equals(status)) {
                assertThat(count).contains("d.status = 'BUSY'");
                assertThat(page).contains("d.status = 'BUSY'");
            }
        }
    }

    @Test
    void unknownCountAndPageMatchPcLifecyclePrecedence() throws Exception {
        for (String methodName : java.util.List.of("countDevices", "pageDevices")) {
            String sql = renderDeviceQuery(methodName, "UNKNOWN").getSql();
            assertThat(sql).as(methodName).contains(
                    // ACTIVE + ONLINE without an activation fact is UNKNOWN.
                    "d.activated_at IS NULL",
                    // ACTIVE + ONLINE with ended activation is UNKNOWN.
                    "d.deactivated_at IS NOT NULL",
                    // A normal active owned Cloud Share with no known runtime is UNKNOWN too.
                    "UPPER(TRIM(COALESCE(r.online_status, ''))) NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')",
                    // Inventory and pending rows retain their higher-priority tabs.
                    "d.pending_deactivate = 0",
                    "'RECYCLED','DEACTIVATED','RETIRED','UNBOUND','INVENTORY','PENDING','PENDING_ACTIVATION','INACTIVE'",
                    // Unknown raw lifecycle remains UNKNOWN even if a runtime row says ONLINE.
                    "UPPER(COALESCE(d.status, '')) NOT IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')");
        }
    }
    @Test
    void inventoryAndUnboundFiltersUsePcAliasesWithoutAbsorbingPendingRows() throws Exception {
        for (String methodName : java.util.List.of("countDevices", "pageDevices")) {
            String inventory = renderDeviceQuery(methodName, "INVENTORY").getSql();
            String unbound = renderDeviceQuery(methodName, "UNBOUND").getSql();
            assertThat(inventory).as(methodName + " inventory")
                    .contains("d.pending_deactivate = 0", "d.status IN ('INVENTORY','INACTIVE','PENDING','PENDING_ACTIVATION')");
            assertThat(unbound).as(methodName + " unbound")
                    .contains("d.pending_deactivate = 0", "d.status IN ('UNBOUND','DEACTIVATED','RECYCLED','RETIRED')");
        }
    }
    @Test
    void overviewAndDatacenterCountersRequireAnActivatedOnlineRuntimeAndKeepExplicitFailuresAbnormal() throws Exception {
        String online = String.join(" ", DeviceOpsMapper.class.getDeclaredMethod("countOnlineDevices")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String offline = String.join(" ", DeviceOpsMapper.class.getDeclaredMethod("countOfflineDevices")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String abnormal = String.join(" ", DeviceOpsMapper.class.getDeclaredMethod("countAbnormalDevices")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String datacenters = String.join(" ", DeviceOpsMapper.class.getDeclaredMethod("datacenterSummaries")
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());

        assertThat(online).contains("nx_user_device_runtime r", "UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'",
                "d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')", "d.activated_at IS NOT NULL",
                "d.pending_deactivate = 0", "UPPER(d.ownership_status) = 'OWNED'");
        assertThat(offline).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'OFFLINE'")
                .doesNotContain("NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')");
        assertThat(abnormal).contains("r.online_status", "d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')",
                "d.activated_at IS NOT NULL", "r.heartbeat_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)");
        assertThat(datacenters).contains("UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'",
                "UPPER(TRIM(COALESCE(r.online_status, ''))) IN ('OFFLINE','ERROR','ABNORMAL','LOST')",
                "d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')", "d.activated_at IS NOT NULL");
    }

    private static BoundSql renderDeviceQuery(String name, String status) throws Exception {
        var method = "countDevices".equals(name)
                ? DeviceOpsMapper.class.getDeclaredMethod(name, String.class, String.class, String.class,
                        Long.class, String.class, String.class)
                : DeviceOpsMapper.class.getDeclaredMethod(name, String.class, String.class, String.class,
                        Long.class, String.class, String.class, long.class, long.class);
        String script = String.join("\n", method.getAnnotation(Select.class).value());
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        params.put("dcLocation", null);
        params.put("keyword", null);
        params.put("userId", null);
        params.put("kind", null);
        params.put("heartbeat", null);
        params.put("limit", 10L);
        params.put("offset", 0L);
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
    }
}
