package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.finance.application.PayoutVndConfigService;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditApprovalRechecksTest {
    @Test
    void d7CoverageCloseoutRequiresTypedBreachAndExactWithdrawalCommand() {
        var breach = mock(PayoutVndConfigService.ConfirmedCoverageBreach.class);
        var d7 = new AuditReplayCommand("D", "d7_payout_vnd_config", Map.of());

        assertThat(AuditApprovalRechecks.confirmed(d7, breach)).isTrue();
        assertThat(AuditApprovalRechecks.confirmed(d7, 409, "D7_TREASURY_COVERAGE_BLOCKED")).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(d7, new BizException(409, "D7_TREASURY_COVERAGE_BLOCKED"))).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(new AuditReplayCommand("D", "d6_fx_quote", Map.of()), breach)).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(new AuditReplayCommand("H", d7.op(), Map.of()), breach)).isFalse();
    }

    @Test
    void h2CloseoutRequiresLockedTerminalStateForCancellation() {
        var cancel = new AuditReplayCommand("H", "h2_trial_cancel", Map.of("sid", "usr_9921"));
        var charge = new AuditReplayCommand("H", "h2_trial_charge", cancel.params());
        String terminal = "TRIAL_SESSION_ALREADY_TERMINAL";

        assertThat(AuditApprovalRechecks.confirmed(cancel, 409, terminal)).isTrue();
        assertThat(AuditApprovalRechecks.confirmed(cancel, 422, terminal)).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(cancel, 409, "TRIAL_SESSION_STATE_CONFLICT")).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(cancel, 422, "TRIAL_SESSION_NOT_FOUND")).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(charge, 409, terminal)).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(charge, 410, "TRIAL_EXPLICIT_PURCHASE_REQUIRED")).isFalse();
        assertThat(AuditApprovalRechecks.confirmed(new AuditReplayCommand("D", cancel.op(), cancel.params()), 409, terminal)).isFalse();
    }
}
