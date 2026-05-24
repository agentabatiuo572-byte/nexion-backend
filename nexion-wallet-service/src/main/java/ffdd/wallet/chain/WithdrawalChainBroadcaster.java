package ffdd.wallet.chain;

import ffdd.wallet.domain.WithdrawalOrder;

public interface WithdrawalChainBroadcaster {
    String broadcast(WithdrawalOrder order);
}
