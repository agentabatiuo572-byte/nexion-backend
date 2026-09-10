package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class SlotCapacityMapperSqlContractTest {

    @Test
    void computeSharePairingCountsEveryOccupiedPhysicalLifecycleState() throws Exception {
        assertPhysicalOccupiedSlotPredicate(select(AppComputeShareEnrollmentMapper.class, "activeDeviceCount"));
    }

    @Test
    void tradeinCapacityCountsEveryOccupiedPhysicalLifecycleState() throws Exception {
        assertPhysicalOccupiedSlotPredicate(select(AppTradeinMapper.class, "countActiveDevices"));
    }

    @Test
    void bundlePreflightCountsEveryOccupiedPhysicalLifecycleState() throws Exception {
        assertPhysicalOccupiedSlotPredicate(select(AppBundleOrderMapper.class, "activeDeviceCount"));
    }

    private static String select(Class<?> mapper, String methodName) throws Exception {
        Method method = mapper.getMethod(methodName, Long.class);
        return String.join(" ", method.getAnnotation(Select.class).value());
    }

    private static void assertPhysicalOccupiedSlotPredicate(String sql) {
        String normalized = sql.replaceAll("\\s+", "");
        assertThat(normalized)
                .contains("UPPER(ownership_status)='OWNED'")
                .contains("UPPER(status)IN('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')")
                .contains("UPPER(COALESCE(NULLIF(device_type,''),'DEVICE'))<>'SHARE'")
                .contains("activated_atISNOTNULL")
                .contains("deactivated_atISNULL")
                .contains("pending_deactivate=0");
    }
}
