package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class AppTradeinEligibilityMapperSqlContractTest {
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
    void sourceEligibilityQueryIsOwnedActiveAndTaskFree() throws Exception {
        Method method = AppTradeinMapper.class.getMethod("listTradeinSourceCandidates", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("d.user_id=#{userId}")
                .contains("UPPER(d.ownership_status)='OWNED'")
                .contains("UPPER(d.status) IN ('ACTIVE','ONLINE')")
                .contains("d.deactivated_at IS NULL")
                .contains("d.pending_deactivate=0")
                .contains("UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'")
                .contains("UPPER(t.status) IN ('CLAIMED','RUNNING')")
                .contains("ORDER BY d.id");
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
                    .contains("UPPER(COALESCE(NULLIF(d.device_type,''),'DEVICE')) <> 'SHARE'");
        }

        for (String methodName : List.of("recycleSourceDevice", "moveSourceDeviceToInventory")) {
            Method method = AppTradeinMapper.class.getMethod(methodName, Long.class, Long.class);
            String sql = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
            assertThat(sql).as(methodName)
                    .contains("UPPER(COALESCE(NULLIF(device_type,''),'DEVICE')) <> 'SHARE'");
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
    void capacityKeepPersistsAPaidOrderAndAnInactiveOwnedDevice() throws Exception {
        Method order = AppTradeinMapper.class.getMethod(
                "insertCapacityKeepOrder", AppTradeinMapper.PaidOrderWrite.class);
        String orderSql = String.join(" ", order.getAnnotation(Insert.class).value());
        assertThat(orderSql)
                .contains("'CAPACITY_KEEP'")
                .contains("'PAID','PAID','WAITING_PROVISIONING'");

        Method device = AppTradeinMapper.class.getMethod(
                "insertInventoryTargetDevice", AppTradeinMapper.DeliveredDeviceWrite.class);
        String deviceSql = String.join(" ", device.getAnnotation(Insert.class).value());
        assertThat(deviceSql)
                .contains("'OWNED','ORDER','INACTIVE'")
                .contains("NOW(),NULL,NULL");
    }

    @Test
    void bothTradeinTargetDeviceInsertBranchesRequireAValidSkuPowerAndDatacenter() throws Exception {
        for (String methodName : List.of("insertTargetDevice", "insertInventoryTargetDevice")) {
            Method method = AppTradeinMapper.class.getMethod(methodName, AppTradeinMapper.DeliveredDeviceWrite.class);
            String sql = String.join(" ", method.getAnnotation(Insert.class).value());

            assertThat(sql).as(methodName)
                    .contains("FROM nx_admin_device_sku s")
                    .contains("s.sku_id=#{row.productNo}")
                    .contains("TRIM(s.datacenter) <> ''")
                    .contains("TRIM(s.power_text) REGEXP '^[0-9]+([.][0-9]+)?[[:space:]]*[Ww]?$'")
                    .contains("CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)) > 0");
        }
    }
}
