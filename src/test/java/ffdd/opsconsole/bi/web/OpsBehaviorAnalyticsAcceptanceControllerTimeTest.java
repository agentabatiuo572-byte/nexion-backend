package ffdd.opsconsole.bi.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.bi.application.BehaviorAnalyticsService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class OpsBehaviorAnalyticsAcceptanceControllerTimeTest {
    @Test
    void convertsExplicitJstTimestampToTheBusinessPlusEightWindowBeforeQueryingFacts() {
        BehaviorAnalyticsService service = mock(BehaviorAnalyticsService.class);
        OpsBehaviorAnalyticsAcceptanceController controller = new OpsBehaviorAnalyticsAcceptanceController(service);

        controller.acceptanceBehavior("h5.20260812", "a".repeat(64), null, null, null,
                OffsetDateTime.parse("2026-08-12T10:15:00+09:00"),
                OffsetDateTime.parse("2026-08-12T10:45:00+09:00"));

        verify(service).acceptanceBehavior(eq("h5.20260812"), eq("a".repeat(64)), eq(null), eq(null), eq(null),
                eq(LocalDateTime.of(2026, 8, 12, 9, 15)), eq(LocalDateTime.of(2026, 8, 12, 9, 45)));
    }
}
