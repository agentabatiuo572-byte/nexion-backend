package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskSignalCategory;
import ffdd.opsconsole.risk.facade.RiskSignalCategorySummaryFacade;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RiskSignalCategorySummaryFacadeAdapter implements RiskSignalCategorySummaryFacade {
    private final B5RiskRadarMapper mapper;

    @Override
    public List<Summary> openCategorySummaries(LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.openCategorySummaries(startAt, endAt).stream().map(row -> {
            RiskSignalCategory category = RiskSignalCategory.valueOf(row.category());
            return new Summary(category.name(), category.displayName(), row.signalCount(), row.accountCount(),
                    row.highestLevel(), row.latestAt());
        }).toList();
    }
}
