package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.G3ScheduleExecutionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
    public void advanceIfDue() {
        LocalDate runDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (executions.claimRunDate(runDate) != 1) return;
        Integer before = (Integer) market.overview().getData().get("activeDayIndex");
        market.advanceScheduledFrame();
        Integer after = (Integer) market.overview().getData().get("activeDayIndex");
        if (!java.util.Objects.equals(before, after)) {
            outbox.publish("NEX_MARKET","weekly","market.curve_advanced",Map.of(
                    "mode","SCHEDULED","runDate",runDate.toString(),"beforeDayIndex",before,"afterDayIndex",after));
        }
        executions.mark(runDate,"SUCCEEDED");
    }

    public Map<String,Object> latestExecution() {
        Map<String,Object> row = executions.latest();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("available", row != null);
        out.put("source","nx_g3_schedule_execution");
        out.put("runDateBasis","UTC_DAY");
        out.put("runDateMeaning","CLAIM_DATE");
        out.put("timestampBasis","DB_SESSION_TIME");
        out.put("oneClaimPerUtcDate",true);
        out.put("failureObservable",false);
        out.put("priceChangeKnown",false);
        out.put("lastAttemptKnown",false);
        if (row == null) {
            out.put("reason","NO_PERSISTED_SCHEDULE_EXECUTION");
            out.put("execution",null);
            return out;
        }
        String status = String.valueOf(row.get("status"));
        out.put("execution", Map.of(
                "runDate", String.valueOf(row.get("runDate")),
                "status", status,
                "claimedAt", String.valueOf(row.get("createdAt")),
                "updatedAt", String.valueOf(row.get("updatedAt"))));
        out.put("executionOutcomeKnown",false);
        out.put("reason", "SUCCEEDED".equals(status) ? "SUCCEEDED_MARK_ONLY"
                : "PROCESSING".equals(status) ? "EXECUTION_NOT_TERMINAL" : "EXECUTION_STATUS_UNKNOWN");
        return out;
    }
}
