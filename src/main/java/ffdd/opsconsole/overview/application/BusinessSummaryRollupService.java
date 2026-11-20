package ffdd.opsconsole.overview.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.overview.mapper.BusinessSummaryMapper;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class BusinessSummaryRollupService {
    private final BusinessSummaryMapper mapper;
    private final Clock clock;

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void refresh() {
        var state=mapper.lockState();
        LocalDateTime asOf=LocalDateTime.ofInstant(clock.instant(),BusinessCalendar.STORAGE_ZONE);
        String signature="B36_20261109_V2:"+BusinessCalendar.legacy().versions();
        boolean rebuild=state.asOf()==null || !signature.equals(state.calendarSignature());
        var dates=new ArrayList<>(mapper.dirtyDates(rebuild?null:state.asOf().minusSeconds(1),asOf));
        LocalDate today=BusinessCalendar.legacy().dateAtStorage(asOf);
        if(!dates.contains(today))dates.add(today);
        if(rebuild && state.statisticsStart()!=null) {
            // Remove old calendar buckets as well as rebuilding the currently mapped source dates.
            for(LocalDate date=state.statisticsStart();!date.isAfter(today);date=date.plusDays(1))
                if(!dates.contains(date))dates.add(date);
        }
        // A plain SELECT shares dirtyDates' snapshot without INSERT...SELECT's source next-key locks.
        var rows=mapper.readDates(dates,asOf);
        mapper.deleteDates(dates);
        if(!rows.isEmpty())mapper.insertDates(rows);
        LocalDate first=dates.stream().min(LocalDate::compareTo).orElse(today);
        LocalDate start=state.statisticsStart()==null || first.isBefore(state.statisticsStart())?first:state.statisticsStart();
        mapper.saveState(start,asOf,signature);
    }
}
