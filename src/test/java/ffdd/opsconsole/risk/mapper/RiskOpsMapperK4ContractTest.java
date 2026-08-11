package ffdd.opsconsole.risk.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
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
    void productionK4BackfillNeverCreatesOrSelectsSandboxScoreUsers() throws Exception {
        assertProductionUserBoundary(
                sql("ensureAllActiveUsersHaveScoreRows", Insert.class));
        assertProductionUserBoundary(sql("scoreUserNos", Select.class));
        assertProductionUserBoundary(
                sql("scoreUserNosNeedingProjection", Select.class, long.class, int.class));
        assertProductionUserBoundary(
                sql("countScoreUsersNeedingProjection", Select.class, long.class));
        assertProductionUserBoundary(sql("scoreRawInput", Select.class, String.class));
    }

    @Test
    void everyProductionK4UserReadRejectsExistingSandboxRowsInSharedRiskTables() throws Exception {
        assertProductionUserBoundary(sql("countScoreUsers", Select.class));
        assertProductionUserBoundary(
                sql("scoreDistributionCounts", Select.class, int.class, int.class));
        assertProductionUserBoundary(sql("findScoreUser", Select.class, String.class));
        assertProductionUserBoundary(sql("findCurrentScoreUser", Select.class, String.class));
        assertProductionUserBoundary(sql("searchScoreUsers", Select.class, String.class, int.class));
    }

    @Test
    void productionK4MutationRootAndCasCannotUpdateSandboxScoreRowsBeforeOutboxPublication()
            throws Exception {
        assertProductionUserBoundary(sql("lockScoreUserForUpdate", Select.class, String.class));
        assertProductionUserBoundary(sql(
                "updateScoreUserModelIfVersion", Update.class,
                String.class, long.class, int.class, String.class));
        assertProductionUserBoundary(sql("advanceScoreAsOfToLatestSource", Update.class, String.class));
        assertProductionUserBoundary(sql(
                "bumpScoreUserVersion", Update.class, String.class, long.class));
    }

    @Test
    void productionSynchronizationRetiresPreexistingSandboxSharedScoreFacts() throws Exception {
        for (String method : List.of(
                "retireOrphanScoreUsers",
                "retireOrphanScoreContributions",
                "deactivateOrphanScoreOverrides")) {
            String statement = sql(method, Update.class);
            assertProductionUserBoundary(statement);
            assertThat(statement).contains("u.id is null");
        }
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
                .contains("row_version=s.row_version+1");
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

    private <A extends Annotation> String sql(
            String methodName, Class<A> annotationType, Class<?>... parameterTypes) throws Exception {
        A annotation = RiskOpsMapper.class.getMethod(methodName, parameterTypes).getAnnotation(annotationType);
        String[] statements;
        if (annotation instanceof Select select) {
            statements = select.value();
        } else if (annotation instanceof Insert insert) {
            statements = insert.value();
        } else if (annotation instanceof Update update) {
            statements = update.value();
        } else {
            throw new AssertionError("Unsupported SQL annotation on " + methodName);
        }
        return String.join(" ", statements).replaceAll("\\s+", " ").toLowerCase();
    }

    private void assertProductionUserBoundary(String sql) {
        assertThat(sql)
                .contains("nx_user u")
                .contains("coalesce(u.sandbox,0)=0");
    }
}
