package ffdd.opsconsole.growth.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.DueTrialRow;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrialLifecycleSchedulerTest {

    @Test
    void settlesEveryDueClaimWithBusinessStableKeyAndSkipsMalformedRows() {
        AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
        AppTrialLifecycleService lifecycle = mock(AppTrialLifecycleService.class);
        LocalDateTime dueAt = LocalDateTime.of(2026, 7, 27, 10, 30);
        when(mapper.dueTrials(100)).thenReturn(List.of(
                new DueTrialRow(7L, "TRIAL-DUE", dueAt),
                new DueTrialRow(null, "INVALID", dueAt)));
        when(lifecycle.settleDue(7L, "TRIAL-DUE", "h2-auto:TRIAL-DUE:20260727T1030"))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        new TrialLifecycleScheduler(mapper, lifecycle).settleDueTrials();

        verify(lifecycle).settleDue(7L, "TRIAL-DUE", "h2-auto:TRIAL-DUE:20260727T1030");
    }
}
