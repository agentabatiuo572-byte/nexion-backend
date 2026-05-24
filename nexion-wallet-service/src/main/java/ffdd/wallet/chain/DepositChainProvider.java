package ffdd.wallet.chain;

import ffdd.wallet.dto.ConfirmDepositRequest;

public interface DepositChainProvider {
    DepositChainConfirmation confirm(ConfirmDepositRequest request);
}
