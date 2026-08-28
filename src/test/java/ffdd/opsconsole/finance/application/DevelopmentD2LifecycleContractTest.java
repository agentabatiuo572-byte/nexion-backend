package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DevelopmentD2LifecycleContractTest {

    @Test
    void simulatorIsDevOnlyServerTimedAndUsesTheCanonicalD2Lifecycle() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/web/DevelopmentD2LifecycleController.java"));
        String simulator = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/DevelopmentD2LifecycleService.java"));
        String finance = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/application/OpsFinanceService.java"));

        assertThat(controller)
                .contains("@Profile(\"dev & !prod\")")
                .contains("/withdrawals/development")
                .contains("/capabilities")
                .contains("/{withdrawalNo}/simulate-cooldown-expiry")
                .contains("finance_d2_read")
                .contains("finance_d2_withdrawal_approve");
        assertThat(simulator)
                .contains("LocalDateTime.now(clock)")
                .contains("minusSeconds(1)")
                .contains("idempotencyService.execute")
                .contains("OBJECT_LOCKED_BY_A2")
                .contains("lockDevelopmentH1Hold")
                .contains("accelerateDevelopmentH1Hold")
                .contains("releaseDueD2Lifecycle")
                .doesNotContain("effectiveNow()")
                .doesNotContain("requestedAt()")
                .doesNotContain("targetTime()");
        assertThat(finance)
                .contains("releaseDueD2Lifecycle(")
                .contains("DEVELOPMENT_SIMULATED_DUE")
                .contains("approvalBlockReason(order, withdrawalDailyLimitCount(), effectiveNow)");
    }

    @Test
    void simulatorSqlIsOrderScopedAndCannotTouchProductionUsersOrStatuses() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/finance/mapper/WithdrawalOrderMapper.java"));

        assertThat(mapper)
                .contains("lockDevelopmentH1Hold")
                .contains("FOR UPDATE")
                .contains("accelerateDevelopmentH1Hold")
                .contains("JOIN nx_user u ON u.id = w.user_id")
                .contains("u.sandbox = 1")
                .contains("w.withdrawal_no = #{withdrawalNo}")
                .contains("w.status = #{expectedStatus}")
                .contains("w.d2_hold_until = #{expectedHoldUntil}")
                .contains("w.d2_lifecycle_owner = 'H1_PHASE_COOLDOWN'")
                .contains("w.d2_previous_status = 'REVIEW_PASSED'");
    }
}
