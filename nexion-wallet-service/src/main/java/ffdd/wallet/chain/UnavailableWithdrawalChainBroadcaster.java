package ffdd.wallet.chain;

import ffdd.wallet.domain.WithdrawalOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(WithdrawalChainBroadcaster.class)
public class UnavailableWithdrawalChainBroadcaster implements WithdrawalChainBroadcaster {
    @Override
    public String broadcast(WithdrawalOrder order) {
        throw new IllegalStateException("Withdrawal chain broadcaster is not configured");
    }
}
