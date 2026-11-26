package ffdd.opsconsole.risk.facade;

import java.time.LocalDateTime;
import java.util.List;

public interface RiskSignalCategorySummaryFacade {
    List<Summary> openCategorySummaries(LocalDateTime startAt, LocalDateTime endAt);

    record Summary(String category, String categoryName, long count, long accountCount, String highestLevel,
                   LocalDateTime latestAt) {
    }
}
