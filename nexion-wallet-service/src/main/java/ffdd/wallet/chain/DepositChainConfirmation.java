package ffdd.wallet.chain;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class DepositChainConfirmation {
    private final String chain;
    private final String chainTxHash;
    private final String asset;
    private final BigDecimal amount;
    private final Integer confirmations;
}
