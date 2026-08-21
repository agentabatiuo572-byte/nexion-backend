package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.QuestCompletionFactConsumer.CompletionResult;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.VoucherGrantFacade;
import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppShareEventServiceTest {
    private final AppGrowthEngagementMapper mapper = mock(AppGrowthEngagementMapper.class);
    private final QuestCompletionFactConsumer factConsumer = mock(QuestCompletionFactConsumer.class);
    private final AppGrowthWheelSandboxService sandbox = mock(AppGrowthWheelSandboxService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AppGrowthEngagementService service = new AppGrowthEngagementService(
            mapper, mock(VoucherGrantFacade.class), mock(GrowthRhythmFacade.class),
            mock(TreasuryCoverageFacade.class), idempotency, audit,
            outbox, null, sandbox, factConsumer, java.util.Optional.empty(), null);

    @BeforeEach
    void setUp() {
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class),
                org.mockito.ArgumentMatchers.<Supplier<ApiResult>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(sandbox.enabled()).thenReturn(false);
        when(sandbox.unknownProfile()).thenReturn(false);
        when(mapper.attribution(42L)).thenReturn(new AppGrowthEngagementMapper.Attribution("P1", 0, "2026-W33"));
    }

    @Test
    void productionSharePostsAStableQuestFactAndReturnsCanonicalScope() {
        when(factConsumer.consume(any())).thenReturn(new CompletionResult("invite_friend", "COMPLETED", false));

        ApiResult<Map<String, Object>> result = service.recordShareEvent(42L,
                new AppGrowthEngagementService.ShareEventRequest(
                        "share-evt-001", "telegram", "share_sheet", "PRODUCTION", ""),
                "share-key-001");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("eventId", "share-evt-001")
                .containsEntry("questCode", "invite_friend")
                .containsEntry("replay", false)
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "");
        verify(factConsumer).consume(any(QuestCompletionFactConsumer.QuestCompletionCommand.class));
        verify(audit).recordRequired(any());
        verify(outbox).publishUserEvent(eq("REFERRAL"), eq("share-evt-001"),
                eq("referral.invite_sent"), eq(42L), eq("P1"), eq(0), eq("2026-W33"), any());
    }

    @Test
    void malformedShareCannotReachQuestFact() {
        assertThatThrownBy(() -> service.recordShareEvent(42L,
                new AppGrowthEngagementService.ShareEventRequest(
                        "bad space", "telegram", "share_sheet", "PRODUCTION", ""),
                "share-key-002"))
                .hasMessage("SHARE_EVENT_ID_INVALID");
        verify(factConsumer, never()).consume(any());
    }

    @Test
    void sandboxShareStaysInRunScopedStoreAndNeverCallsProductionFactConsumer() {
        when(sandbox.enabled()).thenReturn(true);
        when(sandbox.recordShareEvent(eq(42L), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApiResult.ok(Map.of("eventId", "share-evt-003", "sourceEnvironment", "SANDBOX",
                        "runId", "RUN-20260817-01", "serverCanonical", true)));

        ApiResult<Map<String, Object>> result = service.recordShareEvent(42L,
                new AppGrowthEngagementService.ShareEventRequest(
                        "share-evt-003", "copy", "share_sheet", "SANDBOX", "RUN-20260817-01"),
                "share-key-003");

        assertThat(result.getCode()).isZero();
        verify(sandbox).recordShareEvent(42L, "RUN-20260817-01", "share-evt-003", "copy", "share_sheet", "share-key-003");
        verify(factConsumer, never()).consume(any());
        verify(idempotency, never()).execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
    }

    @Test
    void unsupportedChannelCannotReachQuestFact() {
        assertThatThrownBy(() -> service.recordShareEvent(42L,
                new AppGrowthEngagementService.ShareEventRequest(
                        "share-evt-004", "forged", "share_sheet", "PRODUCTION", ""),
                "share-key-004"))
                .hasMessage("SHARE_CHANNEL_INVALID");
        verify(factConsumer, never()).consume(any());
    }
}
