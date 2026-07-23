package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.domain.CopyVersionOptionView;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionCreateRequest;
import ffdd.opsconsole.content.dto.CopyVersionOptionUpdateRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsCopyVersionOptionServiceTest {
    private final CopyAbRepository repository = mock(CopyAbRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final I18nLearningRepository i18nLearningRepository = mock(I18nLearningRepository.class);
    private final Map<String, Object> idempotencyResults = new LinkedHashMap<>();
    private final OpsCopyAbService service = new OpsCopyAbService(
            repository,
            audit,
            Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC),
            OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            idempotency,
            new ObjectMapper().findAndRegisterModules(),
            i18nLearningRepository);

    @BeforeEach
    void positions() {
        when(repository.findPositionForUpdate("home.hero"))
                .thenReturn(Optional.of(new CopyPositionView("home.hero", "首页顶部主视觉", "home", 10, "ACTIVE")));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String cacheKey = invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2);
                    Supplier<Object> action = invocation.getArgument(4);
                    return idempotencyResults.computeIfAbsent(cacheKey, ignored -> action.get());
                });
    }

    @Test
    void overviewExposesVersionOptionsBeforeCopyPoolConsumers() {
        when(repository.listCopies()).thenReturn(List.of());
        when(repository.listExperiments()).thenReturn(List.of());
        when(repository.listVersionOptions()).thenReturn(List.of(
                new CopyVersionOptionView("v2026.1", "2026 首版", "首批越南文案", "ACTIVE", 10, 1L)));

        var result = service.overview();

        assertThat(result.getData().versionOptions()).extracting(CopyVersionOptionView::versionKey)
                .containsExactly("v2026.1");
    }

    @Test
    void versionOptionCrudAuditsAndProtectsReferencedHistory() {
        var createRequest = new CopyVersionOptionCreateRequest(
                "v2026.2", "2026 第二版", "越南语优化", "ACTIVE", 20, "Marina K.", "新增文案版本配置");
        var created = new CopyVersionOptionView("v2026.2", "2026 第二版", "越南语优化", "ACTIVE", 20, 1L);
        var updated = new CopyVersionOptionView("v2026.2", "2026 第二版（调整）", "越南语优化", "INACTIVE", 30, 2L);
        when(repository.findVersionOptionForUpdate("v2026.2"))
                .thenReturn(Optional.empty(), Optional.of(created), Optional.of(updated));
        when(repository.createVersionOption(any(), any())).thenReturn(created);

        assertThat(service.createVersionOption("idem-version-create", createRequest).getData()).isEqualTo(created);

        var updateRequest = new CopyVersionOptionUpdateRequest(
                "2026 第二版（调整）", "越南语优化", "INACTIVE", 30, 1L, "Marina K.", "停用未投放版本配置");
        when(repository.updateVersionOption("v2026.2", updateRequest, Instant.parse("2026-07-12T00:00:00Z")
                .atZone(ZoneOffset.UTC).toLocalDateTime())).thenReturn(updated);
        assertThat(service.updateVersionOption("v2026.2", "idem-version-update", updateRequest).getData()).isEqualTo(updated);
        assertThat(service.updateVersionOption("v2026.2", "idem-version-update", updateRequest).getData()).isEqualTo(updated);
        verify(repository, org.mockito.Mockito.times(1)).updateVersionOption(anyString(), any(), any());

        when(repository.isVersionOptionReferenced("v2026.2")).thenReturn(true);
        var deleted = service.deleteVersionOption("v2026.2", "idem-version-delete",
                new CopyActionRequest("Marina K.", "删除未使用文案版本配置", null, 2L));
        assertThat(deleted.getMessage()).isEqualTo("COPY_VERSION_OPTION_IN_USE");
        verify(audit, org.mockito.Mockito.times(2)).recordRequired(any());
    }

    @Test
    void createCopyRequiresAnActiveCatalogVersionAndKeepsSelectedKey() {
        CopyCreateRequest request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "home", "home.newBanner", "v2026.1",
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增首版三语文案");
        when(repository.findVersionOptionForUpdate("v2026.1")).thenReturn(Optional.of(
                new CopyVersionOptionView("v2026.1", "2026 首版", "首批越南文案", "ACTIVE", 10, 1L)));
        when(repository.findCopy("home.newBanner")).thenReturn(Optional.empty());
        when(repository.listAllVersionNumbers("home.newBanner")).thenReturn(List.of());
        CopyContentRow created = mock(CopyContentRow.class);
        when(created.key()).thenReturn("home.newBanner");
        when(created.i18nKey()).thenReturn("home.newBanner");
        when(created.version()).thenReturn("v2026.1");
        when(created.surface()).thenReturn("home");
        when(repository.createCopy(any(), any())).thenReturn(created);
        when(repository.findVersion("home.newBanner", "v2026.1")).thenReturn(Optional.of(
                new ffdd.opsconsole.content.domain.CopyVersionRow(
                        "home.newBanner", "v2026.1", "published", "created", "07-12 00:00",
                        "新增文案", "New copy", "Bản sao mới", "home.hero", "home", "全量",
                        null, "50", "新增首版")));

        var result = service.createCopy("idem-copy-create", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v2026.1");
        verify(i18nLearningRepository).saveMessagePair(
                "home.newBanner", "新增文案", "New copy", "Bản sao mới", "published",
                Instant.parse("2026-07-12T00:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    @Test
    void createCopyRejectsInactiveOrAlreadyUsedCatalogVersion() {
        CopyCreateRequest request = new CopyCreateRequest(
                "home.newBanner", "新增横幅", "home", "home.newBanner", "v2026.1",
                "全量", "50", "新增首版", "新增文案", "New copy", "Bản sao mới", "home.hero",
                "Marina K.", "新增首版三语文案");
        when(repository.findVersionOptionForUpdate("v2026.1")).thenReturn(Optional.of(
                new CopyVersionOptionView("v2026.1", "2026 首版", "首批越南文案", "INACTIVE", 10, 1L)));
        assertThat(service.createCopy("idem-inactive", request).getMessage()).isEqualTo("COPY_VERSION_OPTION_INACTIVE");

        when(repository.findVersionOptionForUpdate("v2026.1")).thenReturn(Optional.of(
                new CopyVersionOptionView("v2026.1", "2026 首版", "首批越南文案", "ACTIVE", 10, 1L)));
        when(repository.listAllVersionNumbers("home.newBanner")).thenReturn(List.of("v2026.1"));
        assertThat(service.createCopy("idem-used", request).getMessage()).isEqualTo("COPY_VERSION_ALREADY_USED");
    }

    @Test
    void successfulDeleteReplaysWithoutASecondWriteOrAudit() {
        var current = new CopyVersionOptionView(
                "release-2026.08", "八月版本", null, "INACTIVE", 80, 3L, 0L);
        when(repository.findVersionOptionForUpdate("release-2026.08")).thenReturn(Optional.of(current));
        when(repository.isVersionOptionReferenced("release-2026.08")).thenReturn(false);
        var request = new CopyActionRequest("Marina K.", "删除未使用文案版本配置", null, 3L);

        assertThat(service.deleteVersionOption("release-2026.08", "idem-delete-replay", request).getCode()).isZero();
        assertThat(service.deleteVersionOption("release-2026.08", "idem-delete-replay", request).getCode()).isZero();

        verify(repository, org.mockito.Mockito.times(1))
                .deleteVersionOption(eq("release-2026.08"), eq("Marina K."), any());
        verify(audit, org.mockito.Mockito.times(1)).recordRequired(any());
    }
}
