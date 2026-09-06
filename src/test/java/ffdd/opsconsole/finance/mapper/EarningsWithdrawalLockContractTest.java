package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class EarningsWithdrawalLockContractTest {
    @Test
    void finalCapacityLocksTheSameClusterRowsThatK1UpdatesAndLocksProtectedFunds() throws Exception {
        String cluster = String.join(" ", EarningsReleaseMapper.class.getMethod("lockRiskCluster", Long.class)
                .getAnnotation(Select.class).value());
        String protectedRows = String.join(" ", EarningsReleaseMapper.class.getMethod("lockProtectedUsdtAmounts", Long.class)
                .getAnnotation(Select.class).value());
        assertThat(cluster).contains("nx_admin_risk_multi_account_cluster", "FOR UPDATE", "#{userId}");
        assertThat(protectedRows).contains("FOR UPDATE", "asset='USDT'", "#{userId}", "pending_review", "bonus_locked");
        String readOnly = String.join(" ", EarningsReleaseMapper.class.getMethod("riskCluster", Long.class)
                .getAnnotation(Select.class).value());
        assertThat(readOnly).doesNotContain("FOR UPDATE");
    }

    @Test
    void rewardCreditLocksTheSameQualifiedWalletBeforeItInsertsAnEntry() throws Exception {
        String user = String.join(" ", EarningsReleaseMapper.class
                .getMethod("lockCreditUser", Long.class, int.class)
                .getAnnotation(Select.class).value());
        String wallet = String.join(" ", EarningsReleaseMapper.class
                .getMethod("lockCreditWallet", Long.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(user).contains("nx_user", "status='ACTIVE'", "sandbox=#{expectedSandbox}", "FOR UPDATE");
        assertThat(wallet).contains("nx_user_wallet", "sandbox=#{expectedSandbox}", "FOR UPDATE")
                .doesNotContain("JOIN");
    }
}
