package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AppLearningMapperSandboxIsolationContractTest {

    @Test
    void rewardEnvironmentIsOneLockedTriStateSnapshot() throws Exception {
        Select select = AppLearningMapper.class
                .getMethod("lockRewardEnvironment", Long.class)
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("THEN 'SANDBOX'")
                .contains("THEN 'PRODUCTION'")
                .contains("ELSE 'UNKNOWN'")
                .contains("FOR UPDATE");
    }

    @Test
    void productionWeeklyRewardAggregateExcludesSandboxUsersAndWallets() throws Exception {
        Select select = AppLearningMapper.class
                .getMethod("sumGrantedRewardThisWeek")
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("join nx_user u")
                .contains("join nx_user_wallet w")
                .contains("u.sandbox = 0")
                .contains("w.sandbox = 0");
    }

    @Test
    void readProjectionUsesTheSameTriStateUserWalletBoundaryWithoutTakingAWriteLock() throws Exception {
        Select select = AppLearningMapper.class
                .getMethod("readRewardEnvironment", Long.class)
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("THEN 'SANDBOX'")
                .contains("THEN 'PRODUCTION'")
                .contains("ELSE 'UNKNOWN'")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void sandboxMappersCannotWriteOrReadSharedLearningFacts() throws Exception {
        assertSandboxSql("startSandboxCourse");
        assertSandboxSql("recordSandboxQuiz");
        assertSandboxSql("lockSandboxProgress");
        assertSandboxSql("listSandboxProgress");
        assertSandboxSql("findSandboxProgress");
        assertSandboxSql("insertSandboxLearningEvent");
        assertSandboxSql("countSandboxGrantedReward");
        assertSandboxSql("grantSandboxReward");
        assertSandboxSql("sumSandboxGrantedReward");
        assertSandboxSql("claimSandboxQuizIdempotency");
        assertSandboxSql("lockSandboxQuizIdempotency");
        assertSandboxSql("completeSandboxQuizIdempotency");
    }

    private void assertSandboxSql(String methodName) throws Exception {
        var method = java.util.Arrays.stream(AppLearningMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Insert insert = method.getAnnotation(Insert.class);
        Select select = method.getAnnotation(Select.class);
        Update update = method.getAnnotation(Update.class);
        String[] values = insert != null ? insert.value() : select != null ? select.value() : update.value();
        String sql = String.join(" ", values)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        assertThat(sql).contains("nx_learning_sandbox_");
        assertThat(sql).doesNotContain("nx_learning_progress ")
                .doesNotContain("nx_learning_event ")
                .doesNotContain("nx_learning_reward_ledger ");
    }
}
