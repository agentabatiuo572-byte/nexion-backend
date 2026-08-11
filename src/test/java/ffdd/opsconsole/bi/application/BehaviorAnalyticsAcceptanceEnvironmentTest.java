package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import org.junit.jupiter.api.Test;

class BehaviorAnalyticsAcceptanceEnvironmentTest {
    @Test
    void acceptanceAppTelemetryIsServerDerivedSandboxAndHeldOutsideProductionPipelines() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        when(mapper.findTrackedPage("/pages/index/index"))
                .thenReturn(new BehaviorAnalyticsMapper.CatalogRow("/pages/index/index", "首页", 1,
                        "/pages/index/index", "/pages/index/index", true));
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, outbox,
                mock(AuditLogService.class), "unit-test-pseudonym-secret", "SANDBOX");

        var result = service.ingest(42L, new BehaviorEventRequest("a".repeat(32), "app.page_viewed",
                "b".repeat(32), "/pages/index/index", 0L, null, null, null, null,
                System.currentTimeMillis(), "H5", "zh-CN"));

        assertThat(result.getData()).containsEntry("accepted", true)
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("dispatchState", "HOLD");
        verify(outbox, never()).publishTrustedClientAnalyticsEvent(any(), any(), any(), any());
        verify(mapper, never()).insertFact(any());
    }
}
