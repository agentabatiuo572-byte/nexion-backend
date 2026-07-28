package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.DueTrialRow;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advances H2 due sessions even when no App client is open. */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialLifecycleScheduler {
    private static final int BATCH_SIZE = 100;

    private final AppTrialLifecycleMapper mapper;
    private final AppTrialLifecycleService lifecycle;

    @Scheduled(fixedDelayString = "${nexion.trial.scheduler-delay-ms:60000}")
    public void settleDueTrials() {
        List<DueTrialRow> rows = mapper.dueTrials(BATCH_SIZE);
        if (rows == null) return;
        for (DueTrialRow row : rows) {
            if (row == null || row.userId() == null || row.userId() <= 0
                    || row.claimNo() == null || row.claimNo().isBlank() || row.dueAt() == null) {
                continue;
            }
            try {
                String key = "h2-auto:" + row.claimNo() + ":" + row.dueAt()
                        .toString().replaceAll("[^0-9T]", "");
                ApiResult<Map<String, Object>> result = lifecycle.settleDue(
                        row.userId(), row.claimNo(), key);
                // A concurrent user/admin command may have won after the due
                // scan. The lifecycle CAS is authoritative; the next scan
                // observes truth, so a business rejection is not retried here.
                if (result == null) log.error("H2 due settlement returned no result: {}", row.claimNo());
            } catch (RuntimeException ex) {
                log.error("H2 due settlement failed for claim {}", row.claimNo(), ex);
            }
        }
    }
}
