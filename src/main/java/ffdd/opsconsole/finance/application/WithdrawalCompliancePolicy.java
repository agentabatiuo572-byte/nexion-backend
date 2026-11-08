package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.util.StringUtils;

/** H1 review and amount window shared by quoting, D2 review and first payout admission. */
record WithdrawalCompliancePolicy(boolean enabled, int cooldownDays, int month, String phase) {
    static final BigDecimal REVIEW_THRESHOLD_USDT = new BigDecimal("1000");
    static final String REVIEW_REQUIRED = "H1_COMPLIANCE_REVIEW_REQUIRED";
    static final String HOLD_OWNER = "H1_COMPLIANCE_HOLD";

    static WithdrawalCompliancePolicy require(GrowthRhythmSnapshot rhythm) {
        var policy = read(rhythm);
        if (policy == null) throw new BizException(503, "H1_WITHDRAWAL_DIAL_UNAVAILABLE");
        return policy;
    }

    static WithdrawalCompliancePolicy read(GrowthRhythmSnapshot rhythm) {
        if (rhythm == null || !rhythm.reliable() || rhythm.currentMonth() < 1
                || !StringUtils.hasText(rhythm.currentPhase()) || rhythm.withdrawCooldownDays() < 0) {
            return null;
        }
        return new WithdrawalCompliancePolicy(rhythm.complianceHoldEnabled(), rhythm.withdrawCooldownDays(),
                rhythm.currentMonth(), rhythm.currentPhase());
    }

    static boolean supports(String chain) {
        return Set.of("USDT-BEP20", "USDT-ERC20", "USDT-TRC20").contains(chain);
    }

    static boolean h1Hold(String owner, String previousStatus) {
        return HOLD_OWNER.equals(owner) ? "REVIEW_PENDING".equals(previousStatus)
                : owner != null && Set.of("H1_PHASE_COOLDOWN", "H1_ZERO_DAY_AUTO_REVIEW").contains(owner)
                    && "REVIEW_PASSED".equals(previousStatus);
    }

    boolean reviewRequired(String chain) {
        return supports(chain) && enabled;
    }

    boolean holdApplies(String chain, BigDecimal amount) {
        return reviewRequired(chain) && amount.compareTo(REVIEW_THRESHOLD_USDT) > 0;
    }

    int holdDays(String chain, BigDecimal amount) {
        return holdApplies(chain, amount) ? cooldownDays : 0;
    }

    LocalDateTime holdUntil(String chain, BigDecimal amount, LocalDateTime submittedAt) {
        if (submittedAt == null) throw new BizException(503, "WITHDRAWAL_SUBMISSION_TIME_UNAVAILABLE");
        return submittedAt.plusDays(holdDays(chain, amount));
    }

    String versionMaterial() {
        return "H1-COMPLIANCE-THREE-NETWORKS-20261108|" + month + "|" + phase + "|" + enabled + "|" + cooldownDays
                + "|" + REVIEW_THRESHOLD_USDT;
    }

    String approvalOwner() {
        return "H1_COMPLIANCE_APPROVED:M" + month + ":" + phase + ":D" + cooldownDays;
    }
}
