package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.G3ScheduleExecutionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class G3ScheduledAdvanceService {
    private final OpsNexMarketService market;
    private final G3ScheduleExecutionMapper executions;
    private final EventOutboxService outbox;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> advanceIfDue() {
        LocalDate runDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (executions.claimRunDate(runDate) != 1) {
            return Map.of("runDate", runDate.toString(), "claimed", false, "frameApplied", false,
                    "frameAdvanced", false, "priceChanged", false, "outcome", "SKIPPED",
                    "reasonCode", "RUN_DATE_ALREADY_CLAIMED");
        }
        Map<String, Object> beforeState = market.overview().getData();
        Integer before = (Integer) beforeState.get("activeDayIndex");
        BigDecimal beforePrice = (BigDecimal) beforeState.get("currentPrice");
        String noChangeReason = market.advanceScheduledFrame();
        Map<String, Object> afterState = market.overview().getData();
        Integer after = (Integer) afterState.get("activeDayIndex");
        BigDecimal afterPrice = (BigDecimal) afterState.get("currentPrice");
        if (!java.util.Objects.equals(before, after)) {
            outbox.publish("NEX_MARKET","weekly","market.curve_advanced",Map.of(
                    "mode","SCHEDULED","runDate",runDate.toString(),"beforeDayIndex",before,"afterDayIndex",after));
        }
        executions.mark(runDate,"SUCCEEDED");
        boolean applied = noChangeReason == null;
        boolean advanced = applied && !java.util.Objects.equals(before, after);
        Boolean priceChanged = null;
        if (!applied) priceChanged = false;
        else if (beforePrice != null && afterPrice != null) priceChanged = beforePrice.compareTo(afterPrice) != 0;
        String outcome = !applied ? "NO_CHANGE" : advanced ? "ADVANCED"
                : Boolean.TRUE.equals(priceChanged) ? "PRICE_CHANGED"
                : Boolean.FALSE.equals(priceChanged) ? "NO_CHANGE" : "COMPLETED";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runDate", runDate.toString());
        result.put("claimed", true);
        result.put("frameApplied", applied);
        result.put("frameAdvanced", advanced);
        result.put("priceChanged", priceChanged);
        result.put("beforeDayIndex", before);
        result.put("afterDayIndex", after);
        result.put("beforePrice", beforePrice == null ? null : beforePrice.toPlainString());
        result.put("afterPrice", afterPrice == null ? null : afterPrice.toPlainString());
        result.put("outcome", outcome);
        result.put("reasonCode", noChangeReason != null ? noChangeReason
                : "NO_CHANGE".equals(outcome) ? "G3_FRAME_VALUES_UNCHANGED"
                : "COMPLETED".equals(outcome) ? "PRICE_COMPARISON_UNAVAILABLE" : null);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latestExecution() {
        Map<String, Object> execution = executions.latest();
        var attempt = executions.latestAttempt(false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", execution != null || attempt != null);
        result.put("execution", execution);
        result.put("lastAttempt", attempt);
        result.put("lastAdvance", executions.latestAttempt(true));
        result.put("executionOutcomeKnown", attempt != null);
        result.put("priceChangeKnown", attempt != null && attempt.priceChanged() != null);
        result.put("lastAttemptKnown", attempt != null);
        result.put("attemptSelection", "LATEST_PERSISTED_COMPLETION_BY_AUDIT_ID");
        result.put("attemptTimestampBasis", "UTC_APPLICATION_CLOCK");
        result.put("executionTimestampBasis", "DB_SESSION_TIME");
        result.put("runDateBasis", "UTC_CLAIM_DAY");
        result.put("reason", attempt != null ? null : execution == null
                ? "NO_PERSISTED_SCHEDULE_EXECUTION" : "LEGACY_MARK_ONLY");
        result.put("sources", java.util.List.of("nx_g3_schedule_execution", "nx_audit_log", "nx_audit_log_archive"));
        return result;
    }
}
