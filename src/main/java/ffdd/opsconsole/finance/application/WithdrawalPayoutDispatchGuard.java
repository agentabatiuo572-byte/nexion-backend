package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.CregisPayoutControl;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.time.LocalDateTime;
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

    @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public WithdrawalPayoutMapper.PayoutRow begin(WithdrawalPayoutMapper.PayoutRow candidate,LocalDateTime now) {
        var gate=deposits.lockProvisionGate();
        if(gate==null || !"IDLE".equals(gate.get("state")) || !on(gate.get("payoutEnabled")) || !payouts.enabled())return null;
        if(candidate.userId()==null || !"USDT-BEP20".equals(candidate.chain())
                || !candidate.userId().equals(mapper.lockCashCustomer(candidate.userId())))return null;
        if(mapper.claimNewPayout(candidate.withdrawalNo(),now,now.plusMinutes(2))!=1)return null;
        if(mapper.markProviderIntent(candidate.withdrawalNo(),now)!=1)
            throw new IllegalStateException("CREGIS_DISPATCH_INTENT_CONFLICT");
        var claimed=mapper.payout(candidate.withdrawalNo());
        if(claimed==null || !candidate.userId().equals(claimed.userId()) || !"PROCESSING".equals(claimed.status())
                || !"provider".equals(claimed.payoutSource()) || claimed.providerCid()!=null
                || claimed.providerIdempotencyKey()==null || claimed.providerIdempotencyKey().isBlank())
            throw new IllegalStateException("CREGIS_DISPATCH_INTENT_CONFLICT");
        return claimed;
    }

    private static boolean on(Object value) {return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
}
