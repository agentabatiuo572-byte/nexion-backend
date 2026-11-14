package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.G3ScheduleExecutionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
}
