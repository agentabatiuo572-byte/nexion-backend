package ffdd.wallet.chain;

import ffdd.wallet.dto.ConfirmDepositRequest;
import org.springframework.stereotype.Service;

@Service
public class MockDepositChainProvider implements DepositChainProvider {
    @Override
    public DepositChainConfirmation confirm(ConfirmDepositRequest request) {
        return new DepositChainConfirmation(
                request.getChain(),
                request.getChainTxHash(),
                request.getAsset(),
                request.getAmount(),
                request.getConfirmations() == null ? 0 : request.getConfirmations());
    }
}
