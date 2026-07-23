package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyAudienceTarget;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantMetric;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyExperimentCreateRequest;
import ffdd.opsconsole.content.dto.CopyExperimentVariantRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsCopyExperimentCreationServiceTest {
    private static final String COPY_KEY = "home.conversionBanner";
    private final CopyAbRepository repository = mock(CopyAbRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final I18nLearningRepository i18nLearningRepository = mock(I18nLearningRepository.class);
    private final Map<String, Object> idempotencyCache = new LinkedHashMap<>();
    private final OpsCopyAbService service = new OpsCopyAbService(
            repository, audit,
            Clock.fixed(Instant.parse("2026-07-12T03:00:00Z"), ZoneOffset.UTC),
            OpsReadTimeSeedPolicy.enabledForDirectConstruction(), idempotency,
            new ObjectMapper().findAndRegisterModules(), i18nLearningRepository);

    @BeforeEach
    void setUp() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2);
            Supplier<Object> action = invocation.getArgument(4);
            return idempotencyCache.computeIfAbsent(key, ignored -> action.get());
        });
        CopyContentRow copy = mockCopy();
        when(repository.findCopyForUpdate(COPY_KEY)).thenReturn(Optional.of(copy));
        when(repository.findCopy(COPY_KEY)).thenReturn(Optional.of(copy));
        when(repository.findVersion(COPY_KEY, "v6")).thenReturn(Optional.of(version("v6", "archived", audience())));
        when(repository.findVersion(COPY_KEY, "v7")).thenReturn(Optional.of(version("v7", "published", audience())));
    }

    @Test
    void createExperimentPersistsScheduledVariantsAndIsIdempotent() {
        var request = createRequest(50, 50);
        when(repository.hasOtherActiveExperimentForCopy(COPY_KEY, null)).thenReturn(false);
        when(repository.createExperiment(anyString(), any(), anyString(), any())).thenAnswer(invocation ->
                experiment(invocation.getArgument(0), "scheduled"));

        var first = service.createExperiment("idem-exp-create", request);
        var replay = service.createExperiment("idem-exp-create", request);

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().state()).isEqualTo("scheduled");
        assertThat(first.getData().variants()).extracting(CopyExperimentVariantView::version)
                .containsExactly("v6", "v7");
        assertThat(replay.getData().id()).isEqualTo(first.getData().id());
        verify(repository, org.mockito.Mockito.times(1)).createExperiment(anyString(), any(), anyString(), any());
        verify(audit, org.mockito.Mockito.times(1)).recordRequired(any());
    }

    @Test
    void createExperimentRejectsInvalidSplitDraftAndAudienceMismatch() {
        assertThat(service.createExperiment("idem-bad-split", createRequest(40, 50)).getMessage())
                .isEqualTo("COPY_EXPERIMENT_SPLIT_TOTAL_INVALID");

        when(repository.findVersion(COPY_KEY, "v6")).thenReturn(Optional.of(version("v6", "draft", audience())));
        assertThat(service.createExperiment("idem-draft", createRequest(50, 50)).getMessage())
                .isEqualTo("COPY_EXPERIMENT_VERSION_INVALID");

        when(repository.findVersion(COPY_KEY, "v6")).thenReturn(Optional.of(version("v6", "archived", audience())));
        var otherAudience = new CopyAudienceTarget("structured", List.of("vi"), List.of("P2"), 30, null);
        when(repository.findVersion(COPY_KEY, "v7")).thenReturn(Optional.of(version("v7", "published", otherAudience)));
        assertThat(service.createExperiment("idem-audience", createRequest(50, 50)).getMessage())
                .isEqualTo("COPY_EXPERIMENT_AUDIENCE_MISMATCH");
    }

    @Test
    void startExperimentRevalidatesScheduledExperimentAndAttachesCopy() {
        CopyExperimentRow scheduled = experiment("EXP-20260712-ABC", "scheduled");
        CopyExperimentRow running = experiment("EXP-20260712-ABC", "running");
        when(repository.findExperiment("EXP-20260712-ABC")).thenReturn(Optional.of(scheduled), Optional.of(running));
        when(repository.findExperimentForUpdate("EXP-20260712-ABC")).thenReturn(Optional.of(scheduled));
        when(repository.hasOtherActiveExperimentForCopy(COPY_KEY, "EXP-20260712-ABC")).thenReturn(false);
        when(repository.startExperiment("EXP-20260712-ABC", COPY_KEY, "Marina K.",
                Instant.parse("2026-07-12T03:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime())).thenReturn(running);

        var result = service.startExperiment("EXP-20260712-ABC", "idem-exp-start",
                new CopyActionRequest("Marina K.", "启动文案版本实验"));
        var replay = service.startExperiment("EXP-20260712-ABC", "idem-exp-start",
                new CopyActionRequest("Marina K.", "启动文案版本实验"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("running");
        assertThat(replay.getData()).isEqualTo(result.getData());
        verify(repository, org.mockito.Mockito.times(1)).startExperiment(anyString(), anyString(), anyString(), any());
        verify(audit, org.mockito.Mockito.times(1)).recordRequired(any());
    }

    @Test
    void startExperimentRejectsShortReasonAndCorruptedStoredSplit() {
        assertThat(service.startExperiment("EXP-1", "idem-short",
                new CopyActionRequest("Marina K.", "理由太短")).getMessage()).isEqualTo("REASON_REQUIRED");

        CopyExperimentRow invalid = new CopyExperimentRow("EXP-INVALID", COPY_KEY, List.of(
                new CopyExperimentVariantView("A · v6", "v6", 40, BigDecimal.ZERO),
                new CopyExperimentVariantView("B · v7", "v7", 50, BigDecimal.ZERO)),
                "VI P2-P3", "0", "0", "scheduled", "invalid");
        when(repository.findExperiment("EXP-INVALID")).thenReturn(Optional.of(invalid));
        when(repository.findExperimentForUpdate("EXP-INVALID")).thenReturn(Optional.of(invalid));
        when(repository.hasOtherActiveExperimentForCopy(COPY_KEY, "EXP-INVALID")).thenReturn(false);

        assertThat(service.startExperiment("EXP-INVALID", "idem-invalid-split",
                new CopyActionRequest("Marina K.", "启动异常分流文案实验")).getMessage())
                .isEqualTo("COPY_EXPERIMENT_SPLIT_TOTAL_INVALID");
    }

    @Test
    void stopExperimentConcludesAndIsIdempotent() {
        CopyExperimentRow running = experiment("EXP-STOP", "running");
        CopyExperimentRow concluded = experiment("EXP-STOP", "concluded");
        when(repository.findExperiment("EXP-STOP")).thenReturn(Optional.of(running), Optional.of(concluded));
        when(repository.findExperimentForUpdate("EXP-STOP")).thenReturn(Optional.of(running));

        CopyActionRequest request = new CopyActionRequest("Marina K.", "结束文案版本实验并结算");
        var first = service.stopExperiment("EXP-STOP", "idem-exp-stop", request);
        var replay = service.stopExperiment("EXP-STOP", "idem-exp-stop", request);

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().state()).isEqualTo("concluded");
        assertThat(replay.getData()).isEqualTo(first.getData());
        verify(repository, org.mockito.Mockito.times(1))
                .updateExperimentState("EXP-STOP", "concluded", "Marina K.",
                        Instant.parse("2026-07-12T03:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
        verify(audit, org.mockito.Mockito.times(1)).recordRequired(any());
    }

    @Test
    void adoptExperimentPublishesTheUniqueHighestCvrVersionAndIsIdempotent() {
        CopyExperimentRow concluded = experiment("EXP-ADOPT", "concluded");
        CopyExperimentRow adopted = experiment("EXP-ADOPT", "adopted");
        when(repository.findExperiment("EXP-ADOPT")).thenReturn(Optional.of(concluded), Optional.of(adopted));
        when(repository.findExperimentForUpdate("EXP-ADOPT")).thenReturn(Optional.of(concluded));
        when(repository.listExperimentVariantMetrics("EXP-ADOPT")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 100, 10),
                new CopyExperimentVariantMetric("B · v7", "v7", 100, 18)));
        when(repository.adoptExperimentWinner(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(adopted);

        CopyActionRequest request = new CopyActionRequest("Marina K.", "采纳实验胜出文案版本");
        var first = service.adoptExperiment("EXP-ADOPT", "idem-exp-adopt", request);
        var replay = service.adoptExperiment("EXP-ADOPT", "idem-exp-adopt", request);

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().state()).isEqualTo("adopted");
        assertThat(replay.getData()).isEqualTo(first.getData());
        verify(repository, org.mockito.Mockito.times(1))
                .adoptExperimentWinner("EXP-ADOPT", COPY_KEY, "v7", "Marina K.",
                        Instant.parse("2026-07-12T03:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
        verify(i18nLearningRepository).saveMessagePair(
                COPY_KEY, "zh", "en", "vi", "published",
                Instant.parse("2026-07-12T03:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
        verify(audit, org.mockito.Mockito.times(1)).recordRequired(any());
    }

    @Test
    void adoptExperimentRejectsNoExposureAndAnExactCvrTie() {
        CopyExperimentRow concluded = experiment("EXP-ADOPT", "concluded");
        when(repository.findExperiment("EXP-ADOPT")).thenReturn(Optional.of(concluded));
        when(repository.findExperimentForUpdate("EXP-ADOPT")).thenReturn(Optional.of(concluded));
        when(repository.listExperimentVariantMetrics("EXP-ADOPT")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 0, 0),
                new CopyExperimentVariantMetric("B · v7", "v7", 0, 0)));

        CopyActionRequest request = new CopyActionRequest("Marina K.", "采纳实验胜出文案版本");
        var noExposure = service.adoptExperiment("EXP-ADOPT", "idem-no-exposure", request);

        assertThat(noExposure.getCode()).isEqualTo(409);
        assertThat(noExposure.getMessage()).isEqualTo("COPY_EXPERIMENT_NO_EXPOSURE");

        idempotencyCache.clear();
        when(repository.listExperimentVariantMetrics("EXP-ADOPT")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 100, 10),
                new CopyExperimentVariantMetric("B · v7", "v7", 200, 20)));
        var tie = service.adoptExperiment("EXP-ADOPT", "idem-cvr-tie", request);

        assertThat(tie.getCode()).isEqualTo(409);
        assertThat(tie.getMessage()).isEqualTo("COPY_EXPERIMENT_WINNER_NOT_UNIQUE");
        verify(repository, org.mockito.Mockito.never())
                .adoptExperimentWinner(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void adoptExperimentRequiresAtLeastOneHundredExposuresForEveryVariant() {
        CopyExperimentRow concluded = experiment("EXP-SMALL", "concluded");
        when(repository.findExperiment("EXP-SMALL")).thenReturn(Optional.of(concluded));
        when(repository.findExperimentForUpdate("EXP-SMALL")).thenReturn(Optional.of(concluded));
        when(repository.listExperimentVariantMetrics("EXP-SMALL")).thenReturn(List.of(
                new CopyExperimentVariantMetric("A · v6", "v6", 99, 20),
                new CopyExperimentVariantMetric("B · v7", "v7", 200, 20)));

        var result = service.adoptExperiment("EXP-SMALL", "idem-min-sample",
                new CopyActionRequest("Marina K.", "采纳实验胜出文案版本"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("COPY_EXPERIMENT_MIN_SAMPLE_NOT_MET");
        verify(repository, org.mockito.Mockito.never())
                .adoptExperimentWinner(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void discardScheduledOrConcludedExperimentIsIdempotentButRejectsRunning() {
        CopyExperimentRow scheduled = experiment("EXP-DISCARD", "scheduled");
        CopyExperimentRow discarded = experiment("EXP-DISCARD", "discarded");
        when(repository.findExperiment("EXP-DISCARD")).thenReturn(Optional.of(scheduled), Optional.of(discarded));
        when(repository.findExperimentForUpdate("EXP-DISCARD")).thenReturn(Optional.of(scheduled));
        CopyActionRequest request = new CopyActionRequest("Marina K.", "弃用不再需要的文案实验");

        var first = service.discardExperiment("EXP-DISCARD", "idem-exp-discard", request);
        var replay = service.discardExperiment("EXP-DISCARD", "idem-exp-discard", request);

        assertThat(first.getCode()).isZero();
        assertThat(first.getData().state()).isEqualTo("discarded");
        assertThat(replay.getData()).isEqualTo(first.getData());
        verify(repository, org.mockito.Mockito.times(1))
                .updateExperimentState("EXP-DISCARD", "discarded", "Marina K.",
                        Instant.parse("2026-07-12T03:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());

        idempotencyCache.clear();
        CopyExperimentRow concluded = experiment("EXP-CONCLUDED", "concluded");
        CopyExperimentRow discardedAfterConclusion = experiment("EXP-CONCLUDED", "discarded");
        when(repository.findExperiment("EXP-CONCLUDED"))
                .thenReturn(Optional.of(concluded), Optional.of(discardedAfterConclusion));
        when(repository.findExperimentForUpdate("EXP-CONCLUDED")).thenReturn(Optional.of(concluded));
        assertThat(service.discardExperiment("EXP-CONCLUDED", "idem-concluded-discard", request).getCode()).isZero();

        idempotencyCache.clear();
        CopyExperimentRow running = experiment("EXP-RUNNING", "running");
        when(repository.findExperiment("EXP-RUNNING")).thenReturn(Optional.of(running));
        when(repository.findExperimentForUpdate("EXP-RUNNING")).thenReturn(Optional.of(running));
        assertThat(service.discardExperiment("EXP-RUNNING", "idem-running-discard", request).getCode())
                .isEqualTo(409);
    }

    private static CopyExperimentCreateRequest createRequest(int firstSplit, int secondSplit) {
        return new CopyExperimentCreateRequest(COPY_KEY, List.of(
                new CopyExperimentVariantRequest("v6", firstSplit),
                new CopyExperimentVariantRequest("v7", secondSplit)),
                "越南首页文案实验", "Marina K.", "创建两版本文案实验");
    }

    private static CopyAudienceTarget audience() {
        return new CopyAudienceTarget("structured", List.of("vi"), List.of("P2", "P3"), 7, null);
    }

    private static CopyVersionRow version(String key, String status, CopyAudienceTarget audience) {
        return new CopyVersionRow(COPY_KEY, key, status, "chain", "07-12 03:00", "zh", "en", "vi",
                "home.hero", "home", "VI P2-P3", audience, "50", "note");
    }

    private static CopyContentRow mockCopy() {
        CopyContentRow copy = mock(CopyContentRow.class);
        when(copy.key()).thenReturn(COPY_KEY);
        when(copy.i18nKey()).thenReturn(COPY_KEY);
        when(copy.version()).thenReturn("v7");
        return copy;
    }

    private static CopyExperimentRow experiment(String id, String state) {
        return new CopyExperimentRow(id, COPY_KEY, List.of(
                new CopyExperimentVariantView("A · v6", "v6", 50, BigDecimal.ZERO),
                new CopyExperimentVariantView("B · v7", "v7", 50, BigDecimal.ZERO)),
                "VI P2-P3", "0", "0", state, "越南首页文案实验");
    }
}
