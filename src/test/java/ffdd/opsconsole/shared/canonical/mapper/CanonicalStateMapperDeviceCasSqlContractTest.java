package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class CanonicalStateMapperDeviceCasSqlContractTest {

    @Test
    void activationUsesTheSubmittedVersionInItsCompareAndSetWhereClause() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        int activation = mapper.indexOf("int activateOwnedDeviceCas");
        assertThat(activation).isGreaterThanOrEqualTo(0);
        String sql = mapper.substring(Math.max(0, mapper.lastIndexOf("@Update", activation)), activation);
        assertThat(sql).contains(
                "row_version = row_version + 1",
                "AND row_version = #{expectedVersion}");
        assertThat(mapper).contains("@Param(\"expectedVersion\") Long expectedVersion");
    }

    @Test
    void capacityGuardsCountEverySlotOccupyingRuntimeStateButExcludeCloudShare() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "UPPER(ownership_status) = 'OWNED'",
                "UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')",
                "d.activated_at IS NOT NULL AND d.deactivated_at IS NULL AND d.pending_deactivate = 0",
                "UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'",
                "int reservedDeviceOrderCount",
                "nx_order_item",
                "product_type",
                "'PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING'");
    }

    @Test
    void everyDeactivationCasAcceptsTheSameCanonicalActiveStateSet() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        for (String methodName : List.of(
                "markDevicePendingDeactivate", "deactivatePendingDevice", "deactivateOwnedDeviceCas")) {
            int method = mapper.indexOf(" " + methodName + "(");
            assertThat(method).as(methodName).isGreaterThanOrEqualTo(0);
            int sqlStart = mapper.lastIndexOf("@Update", method);
            String sql = mapper.substring(sqlStart, method);
            assertThat(sql).as(methodName)
                    .contains("UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')",
                            "activated_at IS NOT NULL");
        }
    }

    @Test
    void productionAndDevelopmentCapacityUseTheSameActivatedPhysicalAssetDefinition() throws Exception {
        for (String methodName : List.of("activeDeviceCount", "developmentActiveDeviceCount")) {
            var method = CanonicalStateMapper.class.getMethod(methodName, Long.class);
            String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
            assertThat(sql).as(methodName).contains(
                    "UPPER(d.ownership_status)", "'OWNED'",
                    "UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')",
                    "d.activated_at IS NOT NULL", "d.deactivated_at IS NULL", "d.pending_deactivate",
                    "UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'");
        }

        var activation = CanonicalStateMapper.class.getMethod(
                "activateOwnedDeviceCas", Long.class, Long.class, Long.class, Integer.class);
        String activationSql = String.join(" ", activation.getAnnotation(org.apache.ibatis.annotations.Update.class).value())
                .replaceAll("\\s+", " ");
        assertThat(activationSql).contains(
                "UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')",
                "d.activated_at IS NOT NULL", "d.deactivated_at IS NULL", "d.pending_deactivate = 0",
                "UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'");
    }

    @Test
    void cloudShareActivationBypassesThePhysicalSlotCapWhilePhysicalActivationStillUsesCas() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);
        int activation = mapper.indexOf("int activateOwnedDeviceCas");
        String sql = mapper.substring(Math.max(0, mapper.lastIndexOf("@Update", activation)), activation);

        assertThat(sql).contains(
                "UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) = 'SHARE'",
                "UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'",
                "active_snapshot) < #{slotCap}");
    }

    @Test
    void lockedDeviceCommandCarriesActivationFactsBeforeAnAlreadyActiveResponse() throws Exception {
        var command = CanonicalStateMapper.class.getMethod("lockDeviceForUserCommand", Long.class);
        String sql = String.join(" ", command.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("d.activated_at AS activatedAt", "d.deactivated_at AS deactivatedAt", "FOR UPDATE");
        assertThat(Arrays.stream(CanonicalStateMapper.UserDeviceCommandRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly("id", "userId", "instanceNo", "status", "ownershipStatus", "rowVersion",
                        "pendingDeactivate", "deviceType", "activatedAt", "deactivatedAt");
    }
    @Test
    void canonicalOrderReadbackResolvesActivationEvidenceForOrdinaryAndTradeinOrders() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "COALESCE(ud.activated_at, ta.completed_at) AS activatedAt",
                "ud.id = COALESCE(ta.target_device_id",
                "ordinary_device.source_order_no = o.order_no",
                "ordinary_device.user_id = o.user_id",
                "SELECT MIN(ordinary_device.id)");
    }

    @Test
    void ownedDeviceSqlColumnsFollowTheCanonicalRecordConstructorOrder() throws Exception {
        String sql = String.join(" ", CanonicalStateMapper.class
                .getMethod("ownedDevices", Long.class)
                .getAnnotation(Select.class)
                .value())
                .replaceAll("\\s+", " ");

        assertThat(sql).containsSubsequence(
                "d.id,",
                "d.instance_no AS instanceNo,",
                "d.name,",
                "d.device_type AS deviceType,",
                "d.product_code AS productCode,",
                "d.status,",
                "END AS runtimeStatus,",
                "d.row_version AS rowVersion,",
                "d.activated_at AS activatedAt,",
                "d.deactivated_at AS deactivatedAt,",
                "d.purchased_at AS purchasedAt,");
        assertThat(Arrays.stream(CanonicalStateMapper.OwnedDevice.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly(
                        "id", "instanceNo", "name", "deviceType", "productCode", "status", "runtimeStatus", "rowVersion",
                        "pendingDeactivate", "activatedAt", "deactivatedAt", "purchasedAt", "dailyUsdt", "dailyNex", "gpuModel", "vramTotalGb",
                        "basePowerW", "location", "actualPaidUsdt", "cumulativeOutputUsdt");
    }

    @Test
    void ownedDeviceProjectionsReadRuntimeOnceAndNormalizeItToTheAppContract() throws Exception {
        for (String methodName : List.of("ownedDevices", "developmentOwnedDevices", "sandboxOwnedDevices")) {
            var method = "sandboxOwnedDevices".equals(methodName)
                    ? CanonicalStateMapper.class.getMethod(methodName, Long.class, String.class)
                    : CanonicalStateMapper.class.getMethod(methodName, Long.class);
            String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
            assertThat(sql).containsSubsequence(
                    "d.status,", "runtimeStatus", "d.row_version AS rowVersion,",
                    "d.activated_at AS activatedAt,", "d.deactivated_at AS deactivatedAt,",
                    "d.purchased_at AS purchasedAt,");
            if (!"sandboxOwnedDevices".equals(methodName)) {
                assertThat(sql).contains(
                        "LEFT JOIN nx_user_device_runtime runtime",
                        "runtime.user_device_id = d.id AND runtime.is_deleted = 0",
                        "runtime.online_status",
                        "THEN 'ONLINE'",
                        "THEN 'OFFLINE'",
                        "ELSE 'UNKNOWN'",
                        "END AS runtimeStatus");
            }
        }
    }

    @Test
    void realizedTodayProjectionIsProductionOnlyAndGroupedPerDevice() throws Exception {
        var method = CanonicalStateMapper.class.getMethod(
                "realizedToday", Long.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ");

        assertThat(sql).contains(
                "FROM nx_compute_receipt r",
                "COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'",
                "UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')",
                "r.completed_at >= #{start}",
                "r.completed_at < #{end}",
                "GROUP BY r.user_device_id");
        assertThat(sql).doesNotContain("nx_earning_event");
    }

    @Test
    void canonicalDeviceAndEarningsSqlDefendTheProductionSandboxBoundary() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/java/ffdd/opsconsole/shared/canonical/mapper/CanonicalStateMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "COALESCE(u.sandbox,0)=0",
                "COALESCE(r.source_environment, 'PRODUCTION') = 'PRODUCTION'");
        for (String methodName : List.of(
                "activeDeviceCount", "activateOwnedDeviceCas", "lockDeviceForUserCommand",
                "markDevicePendingDeactivate", "deactivateOwnedDeviceCas", "markDeviceRuntimeDeactivated",
                "userCanonicalProfile", "ownedDevices", "realizedToday")) {
            int method = mapper.indexOf(" " + methodName + "(");
            assertThat(method).as(methodName).isGreaterThanOrEqualTo(0);
            int sqlStart = Math.max(mapper.lastIndexOf("@Select", method), mapper.lastIndexOf("@Update", method));
            int sqlEnd = mapper.indexOf("\n    @", method);
            String declaration = mapper.substring(sqlStart, sqlEnd < 0 ? mapper.length() : sqlEnd);
            assertThat(declaration).as(methodName).contains("sandbox");
        }
    }
}
