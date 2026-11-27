package ffdd.opsconsole.team.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record F4SettlementResult(
        Status status, int creditedCount, Map<String, BigDecimal> amountsByCurrency,
        String reasonCode, String settlementKey, String attemptedAt) {

    public enum Status { EXECUTED, NOT_EXECUTED }

    public static F4SettlementResult credited(String settlementKey, int count, String currency, BigDecimal amount) {
        return new F4SettlementResult(Status.EXECUTED, count, Map.of(currency, amount), null, settlementKey, null);
    }

    public static F4SettlementResult notExecuted(String settlementKey, String reasonCode) {
        return new F4SettlementResult(Status.NOT_EXECUTED, 0, Map.of(), reasonCode, settlementKey, null);
    }

    public static F4SettlementResult blockedAttempt(String reasonCode, Instant attemptedAt) {
        return new F4SettlementResult(Status.NOT_EXECUTED, 0, Map.of(), reasonCode, null, attemptedAt.toString());
    }
}
