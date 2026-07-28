package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommissionEventUnlockProcessor {
    private final F5CommissionMapper mapper;
    private final EventOutboxService eventOutboxService;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;

    @Transactional(rollbackFor = Exception.class)
    public boolean unlock(Map<String, Object> row) {
        Long eventId = asLong(row.get("id"));
        Long userId = asLong(row.get("userId"));
        if (eventId == null || userId == null) {
            return false;
        }
        if (mapper.unlockCoolingEventCas(eventId) != 1) {
            return false;
        }
        String currency = String.valueOf(row.getOrDefault("currency", "USDT")).toUpperCase();
        BigDecimal amount = asBigDecimal(row.get("amount"));
        eventOutboxService.publish(
                "COMMISSION",
                String.valueOf(eventId),
                "COMMISSION_UNLOCKED",
                Map.of("user_id", userId, "commission_event_id", eventId));
        if (amount != null && amount.signum() > 0) {
            ledgerPostingFacade.postLedgerEntry(
                    "F5-AUTO-UNLOCK-" + eventId,
                    userId,
                    "TEAM_COMMISSION",
                    currency,
                    "IN",
                    amount,
                    "PENDING",
                    "F5 auto unlock commission | eventId=" + eventId);
        }
        return true;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
