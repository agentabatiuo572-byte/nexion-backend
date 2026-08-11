package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.apache.ibatis.annotations.Select;
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
}
