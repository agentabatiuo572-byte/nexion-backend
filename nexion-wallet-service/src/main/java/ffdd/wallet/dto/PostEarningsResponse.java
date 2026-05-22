package ffdd.wallet.dto;

import ffdd.wallet.domain.WalletLedger;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PostEarningsResponse {
    private int requested;
    private int posted;
    private List<WalletLedger> ledgers;
}
