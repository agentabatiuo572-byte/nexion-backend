package ffdd.wallet.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawalBroadcastResponse {
    private int scanned;
    private int submitted;
    private int failed;
    private int dead;
}
