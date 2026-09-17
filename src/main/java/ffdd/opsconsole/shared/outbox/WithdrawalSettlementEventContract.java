package ffdd.opsconsole.shared.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.shared.exception.BizException;

/** Conditional evidence fields for the two rails sharing D2/L3 settlement events. */
final class WithdrawalSettlementEventContract {
    private WithdrawalSettlementEventContract() {}

    static void validate(String event, JsonNode payload) {
        boolean confirmed = "withdraw.confirmed".equals(event);
        if (!confirmed && !"withdraw.refunded".equals(event)) return;
        boolean bank = "BANK-VND".equals(payload.path("rail").asText());
        if (!bank) {
            // Legacy chain producers omit rail; a bank proof must never substitute for a chain hash.
            require(!payload.has("rail") && !payload.has("provider") && !payload.has("provider_order_id")
                    && !payload.has("amount_vnd"));
            if (confirmed) require(text(payload, "chain_tx_hash"));
            else require(payload.path("risk_score").isNumber());
            return;
        }
        require("HDPAY".equals(payload.path("provider").asText())
                && payload.path("provider_order_id").asText().matches("[1-9][0-9]{0,18}")
                && positive(payload.path("amount_vnd")) && positive(payload.path("amount"))
                && "USDT".equals(payload.path("currency").asText())
                && text(payload, "operator") && text(payload, "reason")
                && !payload.has("chain_tx_hash"));
        if (confirmed) {
            require("CONFIRMED".equals(payload.path("state").asText()) && text(payload, "confirmed_at"));
        } else {
            require("FAILED".equals(payload.path("state").asText()) && text(payload, "address_hash"));
            boolean scored = payload.path("risk_score").isNumber();
            require(scored ? !payload.has("risk_score_status")
                    : !payload.has("risk_score") && "UNAVAILABLE".equals(payload.path("risk_score_status").asText()));
        }
    }

    private static boolean text(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static boolean positive(JsonNode node) {
        return node.isNumber() && node.decimalValue().signum() > 0;
    }

    private static void require(boolean valid) {
        if (!valid) throw new BizException(400, "A4_WITHDRAWAL_SETTLEMENT_EVIDENCE_INVALID");
    }
}
