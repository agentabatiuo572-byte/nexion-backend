package ffdd.opsconsole.treasury.application;

import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nexion.wallet.dual-ledger")
public class TreasuryDualLedgerProperties {
    private BigDecimal reserveUsd = new BigDecimal("5000");
    private BigDecimal nexUsdRate = new BigDecimal("0.17");
    private BigDecimal redlinePct = new BigDecimal("100");
    private BigDecimal healthyPct = new BigDecimal("110");
    private BigDecimal runRiskPct = new BigDecimal("15");
}
