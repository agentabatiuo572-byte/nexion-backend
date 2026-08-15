package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppTradeinEligibilityMapperSqlContractTest {
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
