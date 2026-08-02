package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class RiskOpsMapperK4ContractTest {

    @Test
    void modelProjectionAndBatchTargetsExcludeDeletedOrMissingCanonicalUsers() throws Exception {
        assertTargetsOnlyActiveCanonicalUsers("scoreUserNosNeedingProjection", long.class, int.class);
        assertTargetsOnlyActiveCanonicalUsers("countScoreUsersNeedingProjection", long.class);
        assertTargetsOnlyActiveCanonicalUsers("scoreUserNos");
    }

    @Test
    void globalK4TargetQueriesUseTheSameCanonicalUserLockOrder() throws Exception {
        assertOrderedByUserNo("scoreUserNos");
        assertOrderedByUserNo("scoreUserNosNeedingProjection", long.class, int.class);
    }

    @Test
    void scoreMutationsUseTheScoreUserRowAsThePerUserLockRoot() throws Exception {
        Select select = RiskOpsMapper.class.getMethod("lockScoreUserForUpdate", String.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .contains("from nx_admin_risk_score_user")
                .contains("user_no = #{userno}")
                .contains("for update");
    }

    @Test
    void scoreCasAndOverrideCleanupStayScopedToOneUser() throws Exception {
        assertUserScopedCas("bumpScoreUserVersion", String.class, long.class);
        assertUserScopedCas(
                "updateScoreUserModelIfVersion", String.class, long.class, int.class, String.class);
        Update deactivate = RiskOpsMapper.class.getMethod("deactivateScoreOverrides", String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", deactivate.value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .contains("user_no = #{userno}")
                .contains("active = 1")
                .doesNotContain(" or ");
    }

    private void assertUserScopedCas(String methodName, Class<?>... parameterTypes) throws Exception {
        Update update = RiskOpsMapper.class.getMethod(methodName, parameterTypes).getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql)
                .contains("user_no=#{userno}")
                .contains("row_version=#{expectedversion}")
                .contains("row_version=row_version+1");
    }

    private void assertTargetsOnlyActiveCanonicalUsers(String methodName, Class<?>... parameterTypes)
            throws Exception {
        Select select = RiskOpsMapper.class.getMethod(methodName, parameterTypes).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
                .as("%s must derive K4 targets from active nx_user truth", methodName)
                .contains("join nx_user u", "u.is_deleted=0");
    }

    private void assertOrderedByUserNo(String methodName, Class<?>... parameterTypes) throws Exception {
        Select select = RiskOpsMapper.class.getMethod(methodName, parameterTypes).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql).contains("order by s.user_no");
    }
}
