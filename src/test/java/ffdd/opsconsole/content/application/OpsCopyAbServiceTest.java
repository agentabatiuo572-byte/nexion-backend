package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.CopyAbRepository;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyExperimentVariantView;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyVersionRow;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsCopyAbServiceTest {
    private final FakeCopyAbRepository repository = new FakeCopyAbRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T09:30:00Z"), ZoneOffset.UTC);
    private final OpsCopyAbService service = new OpsCopyAbService(
            repository,
            auditLogService,
            clock,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewReturnsCopyExperimentsFrameworkAndSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().copies()).hasSize(2);
        assertThat(result.getData().experiments()).hasSize(2);
        assertThat(result.getData().frameworkParams()).hasSize(1);
        assertThat(result.getData().sources()).contains("nx_content_copy", "nx_content_experiment_variant");
        assertThat(repository.seedCalls).isEqualTo(1);
    }

    @Test
    void publishRequiresIdempotencyKey() {
        var result = service.publishVersion("home.conversionBanner", null, publishRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void publishRejectsPlaceholderMismatch() {
        var request = new CopyVersionPublishRequest(
                "v8", "Home", "全量", "50", "测试",
                "赚取 {amount} USDT 和 {nex} NEX", "Earn {amount} USDT",
                "Marina K.", "发布文案新版");

        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COPY_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void publishPersistsCurrentVersionAndAudits() {
        var result = service.publishVersion("home.conversionBanner", "idem-i1-pub", publishRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v8");
        assertThat(repository.copies.get("home.conversionBanner").version()).isEqualTo("v8");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I1_COPY_VERSION_PUBLISHED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("CONTENT_COPY");
    }

    @Test
    void saveDraftStoresDraftFields() {
        var result = service.saveDraft("home.conversionBanner", "idem-i1-draft", draftRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().draftVersion()).isEqualTo("v8");
        assertThat(result.getData().draftZh()).contains("{amount}");
    }

    @Test
    void rollbackPublishedVersionReturns409() {
        var result = service.rollbackVersion("home.conversionBanner", "v7", "idem-i1-rollback", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void rollbackArchivedVersionPublishesIt() {
        var result = service.rollbackVersion("home.conversionBanner", "v6", "idem-i1-rollback", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v6");
    }

    @Test
    void archiveCurrentRejectsRepeatedArchive() {
        assertThat(service.archiveCurrent("home.conversionBanner", "idem-i1-archive", actionRequest()).getCode()).isZero();

        var repeated = service.archiveCurrent("home.conversionBanner", "idem-i1-archive-2", actionRequest());

        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateFrameworkParamPersistsAndAudits() {
        var result = service.updateFrameworkParam("split", "idem-i1-fw", new CopyFrameworkUpdateRequest(
                "40/60",
                "Marina K.",
                "调整分流默认"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().current()).isEqualTo("40/60");
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(request ->
                "I1_EXPERIMENT_FRAMEWORK_UPDATED".equals(request.getAction())));
    }

    @Test
    void stopExperimentRequiresRunningState() {
        var result = service.stopExperiment("EXP-2598", "idem-i1-stop", actionRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void stopExperimentUpdatesState() {
        var result = service.stopExperiment("EXP-2611", "idem-i1-stop", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("stopped");
    }

    @Test
    void adoptDiscardedExperimentUpdatesState() {
        var result = service.adoptExperiment("EXP-2598", "idem-i1-adopt", actionRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("adopted");
    }

    private static CopyVersionPublishRequest publishRequest() {
        return new CopyVersionPublishRequest(
                "v8",
                "Home",
                "全量",
                "50",
                "复投文案换版",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Marina K.",
                "发布文案新版");
    }

    private static CopyDraftSaveRequest draftRequest() {
        return new CopyDraftSaveRequest(
                "v8",
                "Home",
                "全量",
                "50",
                "复投文案草稿",
                "完成 {amount} USDT 复投并获得 {nex} NEX 奖励",
                "Reinvest {amount} USDT and earn {nex} NEX",
                "Marina K.",
                "保存草稿版本");
    }

    private static CopyActionRequest actionRequest() {
        return new CopyActionRequest("Marina K.", "内容操作确认");
    }

    private static final class FakeCopyAbRepository implements CopyAbRepository {
        private final Map<String, CopyContentRow> copies = new LinkedHashMap<>();
        private final Map<String, CopyVersionRow> versions = new LinkedHashMap<>();
        private final Map<String, CopyExperimentRow> experiments = new LinkedHashMap<>();
        private final Map<String, CopyFrameworkParamView> framework = new LinkedHashMap<>();
        private int seedCalls;

        private FakeCopyAbRepository() {
            copies.put("home.conversionBanner", copy("home.conversionBanner", "v7", "published", null, null, null));
            copies.put("home.heroCta", copy("home.heroCta", "v9", "published", null, null, null));
            versions.put(key("home.conversionBanner", "v7"), version("home.conversionBanner", "v7", "published"));
            versions.put(key("home.conversionBanner", "v6"), version("home.conversionBanner", "v6", "archived"));
            experiments.put("EXP-2611", experiment("EXP-2611", "running"));
            experiments.put("EXP-2598", experiment("EXP-2598", "discarded"));
            framework.put("split", new CopyFrameworkParamView("split", "分流比例默认", "变体等分", "新实验默认变体等分"));
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<CopyContentRow> listCopies() {
            return new ArrayList<>(copies.values());
        }

        @Override
        public Optional<CopyContentRow> findCopy(String copyKey) {
            return Optional.ofNullable(copies.get(copyKey));
        }

        @Override
        public List<CopyVersionRow> listVersions(String copyKey) {
            return versions.values().stream()
                    .filter(version -> copyKey == null || version.copyKey().equals(copyKey))
                    .toList();
        }

        @Override
        public Optional<CopyVersionRow> findVersion(String copyKey, String version) {
            return Optional.ofNullable(versions.get(key(copyKey, version)));
        }

        @Override
        public List<CopyExperimentRow> listExperiments() {
            return new ArrayList<>(experiments.values());
        }

        @Override
        public Optional<CopyExperimentRow> findExperiment(String experimentId) {
            return Optional.ofNullable(experiments.get(experimentId));
        }

        @Override
        public List<CopyFrameworkParamView> listFrameworkParams() {
            return new ArrayList<>(framework.values());
        }

        @Override
        public void saveDraft(String copyKey, CopyDraftSaveRequest request, LocalDateTime now) {
            CopyContentRow current = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, current.version(), "draft-saved", request.version(), request.zh(), request.en()));
            versions.put(key(copyKey, request.version()), new CopyVersionRow(
                    copyKey,
                    request.version(),
                    "draft",
                    "Marina K. / content lead",
                    "06-18 09:30",
                    request.zh(),
                    request.en(),
                    request.surface(),
                    request.audience(),
                    request.trafficSplit(),
                    request.versionNote()));
        }

        @Override
        public CopyContentRow publishVersion(String copyKey, CopyVersionPublishRequest request, LocalDateTime now) {
            copies.put(copyKey, copy(copyKey, request.version(), "published", null, null, null));
            versions.put(key(copyKey, request.version()), new CopyVersionRow(
                    copyKey,
                    request.version(),
                    "published",
                    "Marina K. / content lead",
                    "06-18 09:30",
                    request.zh(),
                    request.en(),
                    request.surface(),
                    request.audience(),
                    request.trafficSplit(),
                    request.versionNote()));
            return copies.get(copyKey);
        }

        @Override
        public CopyContentRow rollbackVersion(String copyKey, String version, String operator, LocalDateTime now) {
            copies.put(copyKey, copy(copyKey, version, "published", null, null, null));
            return copies.get(copyKey);
        }

        @Override
        public CopyContentRow archiveCurrent(String copyKey, String operator, LocalDateTime now) {
            CopyContentRow current = copies.get(copyKey);
            copies.put(copyKey, copy(copyKey, current.version(), "archived", null, null, null));
            return copies.get(copyKey);
        }

        @Override
        public void updateFrameworkParam(String paramKey, String value, String operator, LocalDateTime now) {
            CopyFrameworkParamView current = framework.get(paramKey);
            framework.put(paramKey, new CopyFrameworkParamView(current.key(), current.name(), value, current.description()));
        }

        @Override
        public void updateExperimentState(String experimentId, String state, String operator, LocalDateTime now) {
            CopyExperimentRow current = experiments.get(experimentId);
            experiments.put(experimentId, new CopyExperimentRow(
                    current.id(),
                    current.copyKey(),
                    current.variants(),
                    current.audience(),
                    current.impressions(),
                    current.conversions(),
                    state,
                    current.note()));
        }

        private static CopyContentRow copy(String key, String version, String status, String draftVersion, String draftZh, String draftEn) {
            return new CopyContentRow(
                    key,
                    key,
                    "Home",
                    version,
                    status,
                    "marketing.home",
                    "EXP-2611",
                    "06-18",
                    draftVersion,
                    draftZh,
                    draftEn,
                    "Home",
                    "全量",
                    "50",
                    "测试");
        }

        private static CopyVersionRow version(String copyKey, String version, String status) {
            return new CopyVersionRow(copyKey, version, status, "seed / lead", "06-01 10:00", "zh", "en", "Home", "全量", "50", "seed");
        }

        private static CopyExperimentRow experiment(String id, String state) {
            return new CopyExperimentRow(
                    id,
                    "home.conversionBanner",
                    List.of(
                            new CopyExperimentVariantView("A", 50, new BigDecimal("4.1")),
                            new CopyExperimentVariantView("B", 50, new BigDecimal("4.8"))),
                    "全量",
                    "412K",
                    "18.3K",
                    state,
                    "测试");
        }

        private static String key(String copyKey, String version) {
            return copyKey + "::" + version;
        }
    }
}
