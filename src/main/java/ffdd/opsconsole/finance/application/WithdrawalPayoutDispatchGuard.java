package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.finance.cregis.CregisPayoutControl;
import ffdd.opsconsole.finance.cregis.CregisNetwork;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The gate is the ordering point shared with stop switches and deposit incidents. */
@Service
@RequiredArgsConstructor
public class WithdrawalPayoutDispatchGuard {
    private final WithdrawalPayoutMapper mapper;
    private final CregisDepositMapper deposits;
    private final CregisPayoutControl payouts;
    private final GrowthRhythmFacade growthRhythm;
    private final AuditLogService audit;

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public WithdrawalPayoutMapper.PayoutRow begin(WithdrawalPayoutMapper.PayoutRow candidate,LocalDateTime now) {
        var gate=deposits.lockProvisionGate();
        if(gate==null || !"IDLE".equals(gate.get("state")) || !on(gate.get("payoutEnabled")) || !payouts.enabled())return null;
        if(candidate.userId()==null || !java.util.Set.of("USDT-BEP20","USDT-ERC20","USDT-TRC20").contains(candidate.chain()))return null;
        var network=CregisNetwork.fromNetwork(candidate.chain().substring(5));
        String enabled=mapper.lockNetworkPayoutEnabled("withdrawal."+network.network().toLowerCase(java.util.Locale.ROOT)+".enabled");
        if(enabled==null || !java.util.Set.of("true","1","on").contains(enabled.trim().toLowerCase(java.util.Locale.ROOT))
                || !payouts.enabled(network)
                || !candidate.userId().equals(mapper.lockCashCustomer(candidate.userId())))return null;
        if (!withdrawalSwitchOpen()) return null;
        if (!complianceAllowsFirstPayout(candidate, now)) return null;
        if(mapper.claimNewPayout(candidate.withdrawalNo(),now,now.plusMinutes(2))!=1)return null;
        if(mapper.markProviderIntent(candidate.withdrawalNo(),now)!=1)
            throw new IllegalStateException("CREGIS_DISPATCH_INTENT_CONFLICT");
        var claimed=mapper.payout(candidate.withdrawalNo());
        if(claimed==null || !candidate.chain().equals(claimed.chain()) || !candidate.userId().equals(claimed.userId()) || !"PROCESSING".equals(claimed.status())
                || !"provider".equals(claimed.payoutSource()) || claimed.providerCid()!=null
                || claimed.providerIdempotencyKey()==null || claimed.providerIdempotencyKey().isBlank())
            throw new IllegalStateException("CREGIS_DISPATCH_INTENT_CONFLICT");
        return claimed;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public WithdrawalPayoutMapper.PayoutRow claimWithoutProvider(WithdrawalPayoutMapper.PayoutRow candidate,
                                                                 LocalDateTime now) {
        if (!withdrawalSwitchOpen()
                || !complianceAllowsFirstPayout(candidate, now)
                || mapper.claimNewPayout(candidate.withdrawalNo(), now, now.plusMinutes(2)) != 1) {
            return null;
        }
        return mapper.payout(candidate.withdrawalNo());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public WithdrawalPayoutMapper.PayoutRow claimDevelopment(WithdrawalPayoutMapper.PayoutRow candidate,
                                                              LocalDateTime now) {
        if (!withdrawalSwitchOpen()
                || ("REVIEW_PASSED".equals(candidate.status()) && !complianceAllowsFirstPayout(candidate, now))
                || mapper.claim(candidate.withdrawalNo(), now, now.plusMinutes(2)) != 1) return null;
        return mapper.payout(candidate.withdrawalNo());
    }

    /** An old automatic approval is recoverable through the ordinary, authorized D2 review queue. */
    private boolean complianceAllowsFirstPayout(WithdrawalPayoutMapper.PayoutRow candidate, LocalDateTime now) {
        if (!WithdrawalCompliancePolicy.supports(candidate.chain())) return true;
        var current = mapper.lockComplianceDispatchState(candidate.withdrawalNo());
        if (current == null || !"REVIEW_PASSED".equals(current.status())
                || current.payoutSource() != null || current.providerCid() != null) return false;
        var compliance = WithdrawalCompliancePolicy.require(growthRhythm.snapshot());
        if (!compliance.reviewRequired(candidate.chain())) return true;
        LocalDateTime holdUntil = compliance.holdUntil(candidate.chain(), candidate.amount(), current.createdAt());
        if (compliance.approvalOwner().equals(current.lifecycleOwner()) && !holdUntil.isAfter(now)) return true;
        if (mapper.returnToComplianceReview(candidate.withdrawalNo(), holdUntil, now) != 1) return false;
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D2_WITHDRAWAL_COMPLIANCE_REVIEW_REQUIRED").resourceType("WITHDRAWAL")
                .resourceId(candidate.withdrawalNo()).bizNo(candidate.withdrawalNo()).userId(candidate.userId())
                .actorType("SYSTEM").actorUsername("withdrawal-payout-admission").riskLevel("HIGH").result("SUCCESS")
                .detail(Map.of("from", current.status(), "to", holdUntil.isAfter(now) ? "EXTENDED_HOLD" : "REVIEW_PENDING",
                        "cause", WithdrawalCompliancePolicy.REVIEW_REQUIRED,
                        "complianceRuleVersion", compliance.versionMaterial(), "holdUntil", holdUntil,
                        "fundsUnchanged", true)).build());
        return false;
    }

    // Repeatable-read also protects a missing primary row while the legacy value governs admission.
    private boolean withdrawalSwitchOpen() {
        String value = mapper.lockWithdrawalSwitch("killswitch.withdraw");
        return KillSwitchState.parse(value == null ? mapper.lockWithdrawalSwitch("emergency.killswitch.withdraw") : value);
    }

    private static boolean on(Object value) {return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
}
