package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class AppTradeinEligibilityMapperSqlContractTest {
    @Test
    void allSourceReadsPreserveZeroActualPaymentAndNeverReplaceItWithCurrentProductPrice() throws Exception {
        String expected = "CASE WHEN o.order_no IS NOT NULL THEN CASE WHEN o.quantity>0 "
                + "THEN o.amount_usdt/o.quantity ELSE NULL END ELSE d.price_usdt_snapshot END AS actualPaidUsdt";
        for (String methodName : List.of("findSourceDevice", "lockSourceDevice", "listTradeinSourceCandidates",
                "findCapacityReplacementSource", "lockCapacityReplacementSource")) {
            Method method = AppTradeinMapper.class.getMethod(methodName,
                    methodName.contains("SourceDevice") ? new Class<?>[] {Long.class, Long.class} : new Class<?>[] {Long.class});
            String sql = String.join(" ", method.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
            assertThat(sql).as(methodName).contains(expected)
                    .doesNotContain("NULLIF(d.price_usdt_snapshot,0)", "p.price_usdt", "NULLIF(CASE WHEN o.quantity");
        }
    }

    @Test
    void tradeinReadsUseRuntimeEnvironmentButWriteLockRemainsProductionOnly() throws Exception {
        Method environment = AppTradeinMapper.class.getMethod("activeUserEnvironment", Long.class);
        String environmentSql = String.join(" ", environment.getAnnotation(Select.class).value());
        assertThat(environmentSql)
                .contains("SELECT sandbox")
                .contains("status='ACTIVE'")
                .contains("is_deleted=0");

        Method lock = AppTradeinMapper.class.getMethod("lockActiveUser", Long.class);
        String lockSql = String.join(" ", lock.getAnnotation(Select.class).value());
        assertThat(lockSql)
                .contains("status='ACTIVE'")
                .contains("is_deleted=0")
                .contains("sandbox=0")
                .contains("FOR UPDATE");
    }

    @Test
    void completedInventoryOrdersAreReadableAndDoNotReserveOrAwaitProvisioningSlots() throws Exception {
        assertThat(DeviceCatalogMapper.ORDER_STATE_SQL).contains("= 'INVENTORY' THEN 'inventory'");
        assertThat(DeviceCatalogMapper.ORDER_STATE_SQL.indexOf("THEN 'inventory'"))
                .isLessThan(DeviceCatalogMapper.ORDER_STATE_SQL.lastIndexOf("THEN 'activated'"));
        String orders = String.join(" ", ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper.class
                .getMethod("userOrdersPage", Long.class, String.class, Integer.class).getAnnotation(Select.class).value());
        assertThat(orders).contains("CASE WHEN o.activation_status='INVENTORY' THEN NULL");
        assertThat(ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper.RESERVED_ORDER_SOURCE)
                .contains("('PENDING_PAYMENT','PAID','PROCESSING','PROVISIONING')")
                .doesNotContain("'COMPLETED'");
        String pending = String.join(" ", DeviceOpsMapper.class.getMethod("countPendingOrdersByDatacenter", String.class)
                .getAnnotation(Select.class).value());
        assertThat(pending).contains("('COMPLETED','CANCELLED'");
        for (String method : List.of("countOrders", "pageOrders")) {
            Method query = java.util.Arrays.stream(DeviceCatalogMapper.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(method)).findFirst().orElseThrow();
            assertThat(String.join(" ", query.getAnnotation(Select.class).value())).contains("THEN 'inventory'", "#{state}");
        }
    }

    @Test
    void sourceEligibilityQueryPreservesActivationOrInventoryPositionAndAllowsLegalTaskCancellation() throws Exception {
        Method method = AppTradeinMapper.class.getMethod("listTradeinSourceCandidates", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("d.user_id=#{userId}")
                .contains("UPPER(d.ownership_status)='OWNED'")
                .contains("UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')")
                .contains("UPPER(d.status) IN ('INACTIVE','INVENTORY','DEACTIVATED','PENDING_ACTIVATION')")
                .contains("d.activated_at IS NOT NULL", "d.activated_at IS NULL", "d.row_version AS rowVersion")
                .contains("d.deactivated_at IS NULL")
                .contains("d.pending_deactivate=0")
                .contains("UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'")
                .contains("d.source_environment='PRODUCTION'", "d.run_id=''")
                .contains("ORDER BY d.id")
                .doesNotContain("SELECT 1 FROM nx_compute_task");
    }

    @Test
    void tradeinSourceRecycleUsesTheExactInstanceAndVersionAfterDeactivation() throws Exception {
        String sql = String.join(" ", AppTradeinMapper.class.getMethod("recycleTradeinSourceDevice",
                Long.class, Long.class, String.class, Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        assertThat(sql).contains("user_id=#{userId}", "instance_no=#{instanceNo}", "row_version=#{expectedVersion}",
                "UPPER(ownership_status)='OWNED'", "activated_at IS NULL", "SELECT 1 FROM nx_compute_task",
                "UPPER(t.status) IN ('CLAIMED','RUNNING')", "ownership_status='RECYCLED'");
    }

    @Test
    void tradeinOrderAndDeviceWritersPersistInheritedPositionWithoutCapacityBranches() throws Exception {
        String order = String.join(" ", AppTradeinMapper.class.getMethod("insertPaidOrder", AppTradeinMapper.PaidOrderWrite.class)
                .getAnnotation(Insert.class).value());
        String device = String.join(" ", AppTradeinMapper.class.getMethod("insertTargetDevice", AppTradeinMapper.DeliveredDeviceWrite.class)
                .getAnnotation(Insert.class).value());
        assertThat(order).contains("'PAID','COMPLETED'", "CASE WHEN #{row.activateTarget} THEN 'ACTIVATED' ELSE 'INVENTORY' END");
        assertThat(device).contains("CASE WHEN #{row.activated} THEN 'ACTIVE' ELSE 'INACTIVE' END",
                "CASE WHEN #{row.activated} THEN NOW() ELSE NULL END");
    }

    @Test
    void everyTradeinSourceReadAndMutationRejectsCloudShare() throws Exception {
        for (String methodName : List.of(
                "findSourceDevice", "lockSourceDevice", "listTradeinSourceCandidates",
                "findCapacityReplacementSource", "lockCapacityReplacementSource")) {
            Method method = AppTradeinMapper.class.getMethod(methodName,
                    methodName.contains("SourceDevice") ? new Class<?>[] {Long.class, Long.class}
                            : new Class<?>[] {Long.class});
            String sql = String.join(" ", method.getAnnotation(Select.class).value());
            assertThat(sql).as(methodName)
                    .contains("UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'")
                    .contains("UPPER(COALESCE(d.source_channel,'')) <> 'PROMOTION_GIFT'");
        }

        for (String methodName : List.of("recycleSourceDevice", "moveSourceDeviceToInventory")) {
            Method method = AppTradeinMapper.class.getMethod(methodName, Long.class, Long.class);
            String sql = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
            assertThat(sql).as(methodName)
                    .contains("UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'")
                    .contains("UPPER(COALESCE(source_channel,'')) <> 'PROMOTION_GIFT'");
        }
    }

    @Test
    void targetLookupCarriesReleasePhaseAlongsideInventoryTruth() throws Exception {
        Method method = AppTradeinMapper.class.getMethod("findTargetProduct", Long.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("store_visible=1")
                .contains("UPPER(status) IN ('ACTIVE','ON_SALE')")
                .contains("unlock_phase AS unlockPhase");
    }

    @Test
    void capacityKeepPersistsACompletedOrderAndAnActivatedOwnedDevice() throws Exception {
        Method order = AppTradeinMapper.class.getMethod(
                "insertCapacityKeepOrder", AppTradeinMapper.PaidOrderWrite.class);
        String orderSql = String.join(" ", order.getAnnotation(Insert.class).value());
        assertThat(orderSql)
                .contains("'CAPACITY_KEEP'")
                .contains("'PAID','COMPLETED','ACTIVATED'");

        Method device = AppTradeinMapper.class.getMethod(
                "insertCapacityKeepTargetDevice", AppTradeinMapper.DeliveredDeviceWrite.class);
        String deviceSql = String.join(" ", device.getAnnotation(Insert.class).value());
        assertThat(deviceSql)
                .contains("'OWNED','ORDER','ACTIVE'")
                .contains("NOW(),NOW(),NOW()");
    }

    @Test
    void bothTradeinTargetDeviceInsertBranchesRequireAValidSkuPowerAndDatacenter() throws Exception {
        for (String methodName : List.of("insertTargetDevice", "insertCapacityKeepTargetDevice")) {
            Method method = AppTradeinMapper.class.getMethod(methodName, AppTradeinMapper.DeliveredDeviceWrite.class);
            String sql = String.join(" ", method.getAnnotation(Insert.class).value());

            assertThat(sql).as(methodName)
                    .contains("FROM nx_admin_device_sku s")
                    .contains("s.sku_id=#{row.productNo}")
                    .contains("TRIM(s.datacenter) <> ''")
                    .contains("UPPER(TRIM(s.power_text)) REGEXP '^([0-9]{1,12}|[0-9]{1,3}(,[0-9]{3}){1,3})([.][0-9]{1,6})?[[:space:]]*(W([[:space:]]+TDP|[[:space:]]*(（(单卡额定|显卡额定合计)）|[(](单卡额定|显卡额定合计)[)]))?)?\\\\z'")
                    .contains("CAST(REPLACE(REGEXP_SUBSTR(s.power_text,'[0-9][0-9,.]*'),',','') AS DECIMAL(18,6)) > 0");
        }
    }
}
