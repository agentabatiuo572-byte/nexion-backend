package ffdd.wallet.chain;

import ffdd.wallet.domain.WithdrawalOrder;
import org.springframework.stereotype.Component;

@Component
public class UnavailableWithdrawalChainBroadcaster implements WithdrawalChainBroadcaster {
    @Override
    public String broadcast(WithdrawalOrder order) {
        throw new IllegalStateException("Withdrawal chain broadcaster is not configured");
    }
}
