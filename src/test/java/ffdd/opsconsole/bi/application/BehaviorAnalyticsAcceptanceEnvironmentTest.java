package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsSandboxMapper;
import ffdd.opsconsole.bi.web.BehaviorEventRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BehaviorAnalyticsAcceptanceEnvironmentTest {
    @Test
    void acceptanceAppTelemetryIsServerDerivedSandboxAndHeldOutsideProductionPipelines() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        when(mapper.findTrackedPage("/pages/index/index"))
                .thenReturn(new BehaviorAnalyticsMapper.CatalogRow("/pages/index/index", "首页", 1,
                        "/pages/index/index", "/pages/index/index", true));
        when(mapper.isSandboxUser(42L)).thenReturn(true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), environment, "unit-test-pseudonym-secret", "unit-run");
        when(sandboxMapper.tryAcquireSessionLock(any())).thenReturn(1);

        var result = service.ingest(42L, new BehaviorEventRequest("a".repeat(32), "app.page_viewed",
                "b".repeat(32), "/pages/index/index", 0L, null, null, null, null,
                System.currentTimeMillis(), "H5", "zh-CN"));

        assertThat(result.getData()).containsEntry("accepted", true)
                .containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX");
        verify(outbox, never()).publishTrustedClientAnalyticsEvent(any(), any(), any(), any());
        verify(mapper, never()).insertFact(any());
        verify(sandboxMapper).insertFact(any());
    }

    @Test
    void mixedProfilesAndAccountEnvironmentMismatchesFailClosedBeforeAnyFactWrite() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
        EventOutboxService outbox = mock(EventOutboxService.class);
        when(mapper.findTrackedPage("/pages/index/index"))
                .thenReturn(new BehaviorAnalyticsMapper.CatalogRow("/pages/index/index", "首页", 1,
                        "/pages/index/index", "/pages/index/index", true));
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("dev", "prod");
        BehaviorAnalyticsService mixedService = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), mixed, "unit-test-pseudonym-secret", "unit-run");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> mixedService.ingest(42L, event())))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ANALYTICS_PROFILE_FORBIDDEN");
        verify(sandboxMapper, never()).insertFact(any());
        verify(mapper, never()).insertFact(any());
        verify(outbox, never()).publishTrustedClientAnalyticsEvent(any(), any(), any(), any());

        MockEnvironment acceptance = new MockEnvironment();
        acceptance.setActiveProfiles("dev");
        BehaviorAnalyticsService accountMismatch = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), acceptance, "unit-test-pseudonym-secret", "unit-run");
        when(mapper.isSandboxUser(42L)).thenReturn(false);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> accountMismatch.ingest(42L, event())))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCOUNT_ENVIRONMENT_MISMATCH");

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        BehaviorAnalyticsService productionMismatch = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), production, "unit-test-pseudonym-secret", "unit-run");
        when(mapper.isSandboxUser(42L)).thenReturn(true);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> productionMismatch.ingest(42L, event())))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCOUNT_ENVIRONMENT_MISMATCH");

        MockEnvironment unknown = new MockEnvironment();
        unknown.setActiveProfiles("preview");
        BehaviorAnalyticsService unknownProfile = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox,
                mock(AuditLogService.class), unknown, "unit-test-pseudonym-secret", "unit-run");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> unknownProfile.ingest(42L, event())))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ANALYTICS_PROFILE_FORBIDDEN");
    }

    @Test
    void acceptanceObservationRequiresRunProfileAndReturnsProductionPipelineDeltaEvidence() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
        MockEnvironment acceptance = new MockEnvironment();
        acceptance.setActiveProfiles("dev");
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, sandboxMapper, mock(EventOutboxService.class),
                mock(AuditLogService.class), acceptance, "unit-test-pseudonym-secret", "run-l6-1");
        String actorHash = "a".repeat(64);
        String sessionHash = "b".repeat(64);
        LocalDateTime from = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime to = from.plusMinutes(30);
        LocalDateTime ingestedAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        when(mapper.findTrackedPage("/pages/index/index"))
                .thenReturn(new BehaviorAnalyticsMapper.CatalogRow("/pages/index/index", "首页", 1,
                        "/pages/index/index", "/pages/index/index", true));
        when(sandboxMapper.summary("run-l6-1", actorHash, sessionHash, "/pages/index/index", from, to))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.SandboxSummary(2L, 3L));
        when(sandboxMapper.ingestWindow("run-l6-1", actorHash, sessionHash, "/pages/index/index", from, to))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.IngestWindow(ingestedAt, ingestedAt));
        when(sandboxMapper.productionFactDelta(actorHash, sessionHash, ingestedAt.minusMinutes(1), ingestedAt.plusMinutes(1))).thenReturn(0L);
        when(sandboxMapper.productionOutboxDelta(sessionHash, ingestedAt.minusMinutes(1), ingestedAt.plusMinutes(1))).thenReturn(0L);

        var result = service.acceptanceBehavior("run-l6-1", actorHash, sessionHash, "/pages/index/index", from, to);

        assertThat(result.getData()).containsEntry("source", "mock").containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("pageViews", 2L).containsEntry("clicks", 3L);
        assertThat(result.getData()).containsKey("productionDelta");
    }

    @Test
    void acceptanceObservationRejectsUnscopedProductionDeltaEvidence() {
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mock(BehaviorAnalyticsMapper.class),
                mock(BehaviorAnalyticsSandboxMapper.class), mock(EventOutboxService.class),
                mock(AuditLogService.class), acceptanceEnvironment(), "unit-test-pseudonym-secret", "run-l6-1");
        LocalDateTime from = LocalDateTime.of(2026, 8, 12, 10, 0);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.acceptanceBehavior("run-l6-1", null, "b".repeat(64), null, from, from.plusMinutes(1))))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCEPTANCE_SCOPE_REQUIRED");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.acceptanceBehavior("run-l6-1", "a".repeat(64), null, null, from, from.plusMinutes(1))))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCEPTANCE_SCOPE_REQUIRED");
    }

    @Test
    void acceptanceObservationRejectsZeroMatchedFactsInsteadOfManufacturingGreenEvidence() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, sandboxMapper,
                mock(EventOutboxService.class), mock(AuditLogService.class), acceptanceEnvironment(),
                "unit-test-pseudonym-secret", "run-l6-1");
        String actorHash = "a".repeat(64);
        String sessionHash = "b".repeat(64);
        LocalDateTime from = LocalDateTime.of(2026, 8, 12, 10, 0);
        when(sandboxMapper.summary("run-l6-1", actorHash, sessionHash, null, from, from.plusMinutes(1)))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.SandboxSummary(0L, 0L));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.acceptanceBehavior("run-l6-1", actorHash, sessionHash, null, from, from.plusMinutes(1))))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCEPTANCE_FACTS_NOT_FOUND");
    }

    @Test
    void receiptCredentialResolvesScopeAndAnyProductionDeltaFailsClosed() {
        BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
        BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
        BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, sandboxMapper,
                mock(EventOutboxService.class), mock(AuditLogService.class), acceptanceEnvironment(),
                "unit-test-pseudonym-secret", "run-l6-1");
        String actorHash = "a".repeat(64);
        String sessionHash = "b".repeat(64);
        String token = "c".repeat(64);
        LocalDateTime from = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime to = from.plusMinutes(1);
        LocalDateTime ingestedAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        when(sandboxMapper.findObservationScope(token))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.ObservationScope("run-l6-1", actorHash, sessionHash));
        when(sandboxMapper.summary("run-l6-1", actorHash, sessionHash, null, from, to))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.SandboxSummary(1L, 0L));
        when(sandboxMapper.ingestWindow("run-l6-1", actorHash, sessionHash, null, from, to))
                .thenReturn(new BehaviorAnalyticsSandboxMapper.IngestWindow(ingestedAt, ingestedAt));
        when(sandboxMapper.productionFactDelta(actorHash, sessionHash, ingestedAt.minusMinutes(1), ingestedAt.plusMinutes(1))).thenReturn(1L);
        when(sandboxMapper.productionOutboxDelta(sessionHash, ingestedAt.minusMinutes(1), ingestedAt.plusMinutes(1))).thenReturn(0L);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.acceptanceBehavior("run-l6-1", token, null, null, null, from, to)))
                .isInstanceOf(BizException.class).hasMessageContaining("L6_ACCEPTANCE_PRODUCTION_CONTAMINATION");
    }

    @Test
    void directProductionL6RoutesFailClosedInEveryIsolatedMixedOrUnknownProfileBeforeReadsAuditOrOutbox() {
        for (String[] profiles : new String[][] { { "dev" }, { "test" }, { "dev" },
                { "dev", "prod" }, { "preview" } }) {
            BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
            BehaviorAnalyticsSandboxMapper sandboxMapper = mock(BehaviorAnalyticsSandboxMapper.class);
            EventOutboxService outbox = mock(EventOutboxService.class);
            AuditLogService audit = mock(AuditLogService.class);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper, sandboxMapper, outbox, audit,
                    environment, "unit-test-pseudonym-secret", "run-l6-1");

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.behavior("7d", "ALL", "ALL", "ALL", "pv")))
                    .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.clickHeat("/pages/index/index", "7d", "ALL", "ALL", "L3")))
                    .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(service::pageCatalog))
                    .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.exportBehavior("7d", "ALL", "ALL", "ALL", "pv")))
                    .isInstanceOf(BizException.class).hasMessageContaining("L6_PRODUCTION_SURFACE_FORBIDDEN");
            verifyNoInteractions(mapper, sandboxMapper, outbox, audit);
        }
    }

    @Test
    void productionAndLegacyDefaultProfilesRetainTheProductionReadSurface() {
        for (String[] profiles : new String[][] { { "prod" }, {} }) {
            BehaviorAnalyticsMapper mapper = mock(BehaviorAnalyticsMapper.class);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            BehaviorAnalyticsService service = new BehaviorAnalyticsService(mapper,
                    mock(BehaviorAnalyticsSandboxMapper.class), mock(EventOutboxService.class), mock(AuditLogService.class),
                    environment, "unit-test-pseudonym-secret", "run-l6-1");
            when(mapper.activity(any(), any(), any(), any(), any())).thenReturn(List.of());
            assertThatCode(() -> service.behavior("7d", "ALL", "ALL", "ALL", "pv")).doesNotThrowAnyException();
        }
    }

    private MockEnvironment acceptanceEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return environment;
    }

    private BehaviorEventRequest event() {
        return new BehaviorEventRequest("c".repeat(32), "app.page_viewed", "d".repeat(32), "/pages/index/index",
                0L, null, null, null, null, System.currentTimeMillis(), "H5", "zh-CN");
    }
}
