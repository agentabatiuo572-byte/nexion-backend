package ffdd.opsconsole.treasury.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

public record TreasuryLedgerBillView(
        Long id,
        Long userId,
        String userNo,
        String nickname,
        String bizNo,
        String bizType,
        String asset,
        String direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    @JsonProperty("billType")
    public String billType() {
        String raw = bizType == null ? "" : bizType.trim().toUpperCase(Locale.ROOT);
        if (raw.contains("BONUS") || raw.contains("TRIAL")) return "bonus";
        if (raw.contains("TOPUP") || raw.contains("DEPOSIT") || raw.contains("RECHARGE")) return "topup";
        if (raw.contains("WITHDRAW") || raw.contains("PAYOUT")) return "withdraw";
        if (raw.contains("COMMISSION")) return "commission";
        if (raw.contains("REFUND") || raw.contains("CHARGEBACK") || raw.contains("REVERSAL")) return "refund";
        if (raw.contains("SWAP") || raw.contains("EXCHANGE") || raw.contains("CONVERT")) return "swap";
        return "earning";
    }

    @JsonProperty("subtype")
    public String subtype() {
        return bizType == null ? "" : bizType.trim().toLowerCase(Locale.ROOT);
    }
}
