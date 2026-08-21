package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppTradeinEligibilityMapperSqlContractTest {
    @Test
    void tradeinUserEnvironmentAndWriteLockAreProductionOnly() throws Exception {
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
                .contains("UPPER(t.status) IN ('CLAIMED','RUNNING')")
                .contains("ORDER BY d.id");
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
}
