package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.DisclosureChapterView;
import ffdd.opsconsole.content.domain.DisclosureDraftView;
import ffdd.opsconsole.content.domain.DisclosureGateActionView;
import ffdd.opsconsole.content.domain.DisclosureJurisdictionView;
import ffdd.opsconsole.content.domain.FinancialFieldView;
import ffdd.opsconsole.content.domain.TrustDisclosureRepository;
import ffdd.opsconsole.content.domain.TrustSectionFieldView;
import ffdd.opsconsole.content.domain.TrustSectionView;
import ffdd.opsconsole.content.dto.DisclosureDraftRequest;
import ffdd.opsconsole.content.dto.DisclosureGateUpdateRequest;
import ffdd.opsconsole.content.dto.TrustDisclosureActionRequest;
import ffdd.opsconsole.content.dto.TrustSectionPublishRequest;
import ffdd.opsconsole.content.dto.TrustSectionRollbackRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsTrustDisclosureServiceTest {
    private final FakeTrustDisclosureRepository repository = new FakeTrustDisclosureRepository();
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:30:00Z"), ZoneOffset.UTC);
    private final OpsTrustDisclosureService service = new OpsTrustDisclosureService(
            repository,
            configFacade,
            auditLogService,
            clock,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewReturnsTrustDisclosureDataAndSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().trustSections()).hasSize(2);
        assertThat(result.getData().jurisdictions()).hasSize(2);
        assertThat(result.getData().chapters()).hasSize(2);
        assertThat(result.getData().stats().staleAckUsers()).isEqualTo(2632);
        assertThat(result.getData().gateScope()).isEqualTo("提现");
        assertThat(result.getData().sources()).contains("nx_trust_section", "nx_disclosure_draft");
        assertThat(repository.seedCalls).isEqualTo(1);
        verify(configFacade).upsertAdminValue("disclosure.gate.withdraw", "true", "BOOLEAN", "content", "I4 overview gate sync");
        verify(configFacade).upsertAdminValue("disclosure.gate.staking", "false", "BOOLEAN", "content", "I4 overview gate sync");
    }

    @Test
    void publishSectionRequiresIdempotencyKey() {
        var result = service.publishSection("financials", null, new TrustSectionPublishRequest(
                "v6",
                "Marina K.",
                "发布信任版块"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void rollbackSameVersionReturns409() {
        var result = service.rollbackSection("financials", "idem-i4-roll", new TrustSectionRollbackRequest(
                "v5",
                "Marina K.",
                "回滚信任版块"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void archiveSectionRejectsRepeatedArchive() {
        assertThat(service.archiveSection("leadership", "idem-i4-archive", actionRequest()).getCode()).isZero();

        var repeated = service.archiveSection("leadership", "idem-i4-archive-2", actionRequest());

        assertThat(repeated.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void saveDraftRejectsManualUrlAndRawJson() {
        var manualUrl = service.saveDisclosureDraft("SFC", "idem-i5-draft", new DisclosureDraftRequest(
                "v13",
                "SFC",
                "en+zh",
                "2026-06-30",
                true,
                "请访问 https://example.com 查看披露",
                "Visit https://example.com for disclosure",
                "Marina K.",
                "保存披露草稿"));
        assertThat(manualUrl.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());

        var rawJson = service.saveDisclosureDraft("SFC", "idem-i5-draft-2", new DisclosureDraftRequest(
                "v13",
                "SFC",
                "en+zh",
                "2026-06-30",
                true,
                "{\"zh\":\"raw\"}",
                "{\"en\":\"raw\"}",
                "Marina K.",
                "保存披露草稿"));
        assertThat(rawJson.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void updateGateRejectsSunsetFeatureReactivation() {
        var result = service.updateGateScope("idem-i5-gate", new DisclosureGateUpdateRequest(
                "提现 + NEX v2 锁仓",
                "Marina K.",
                "调整合规闸范围"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.RETIRED_FEATURE.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
    }

    @Test
    void updateGatePersistsActiveScopeWithoutSunsetAction() {
        var result = service.updateGateScope("idem-i5-gate", new DisclosureGateUpdateRequest(
                "提现 + 质押锁仓",
                "Marina K.",
                "调整合规闸范围"));

        assertThat(result.getCode()).isZero();
        assertThat(repository.activeGateKeys).containsExactlyInAnyOrder("withdraw", "staking");
        assertThat(result.getData().gatedActions()).filteredOn(DisclosureGateActionView::active)
                .extracting(DisclosureGateActionView::key)
                .containsExactlyInAnyOrder("withdraw", "staking");
        assertThat(result.getData().gatedActions()).filteredOn(action -> action.key().equals("nexv2"))
                .allSatisfy(action -> assertThat(action.active()).isFalse());
        verify(configFacade).upsertAdminValue("disclosure.gate.withdraw", "true", "BOOLEAN", "content", "调整合规闸范围");
        verify(configFacade).upsertAdminValue("disclosure.gate.staking", "true", "BOOLEAN", "content", "调整合规闸范围");
    }

    @Test
    void publishDisclosurePersistsReackAndAudits() {
        var result = service.publishDisclosure("SFC", "idem-i5-publish", disclosureRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().version()).isEqualTo("v13");
        assertThat(result.getData().ackProgress()).isZero();
        assertThat(repository.drafts.get("SFC::v13").status()).isEqualTo("published");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I5_DISCLOSURE_PUBLISHED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("DISCLOSURE_JURISDICTION");
    }

    private static TrustDisclosureActionRequest actionRequest() {
        return new TrustDisclosureActionRequest("Marina K.", "内容操作确认");
    }

    private static DisclosureDraftRequest disclosureRequest() {
        return new DisclosureDraftRequest(
                "v13",
                "SFC",
                "en+zh",
                "2026-06-30",
                true,
                "收益估算不构成承诺，用户需确认 {version}",
                "Earnings estimates are not guarantees, please acknowledge {version}",
                "Marina K.",
                "发布披露新版");
    }

    private static final class FakeTrustDisclosureRepository implements TrustDisclosureRepository {
        private final Map<String, TrustSectionView> sections = new LinkedHashMap<>();
        private final Map<String, DisclosureJurisdictionView> jurisdictions = new LinkedHashMap<>();
        private final Map<String, DisclosureDraftView> drafts = new LinkedHashMap<>();
        private final Map<String, DisclosureGateActionView> gates = new LinkedHashMap<>();
        private final Set<String> activeGateKeys = new LinkedHashSet<>();
        private int seedCalls;

        private FakeTrustDisclosureRepository() {
            sections.put("financials", new TrustSectionView("financials", "财务透明数字组", "数字组 + 脚注", "v5", "published", "05-12", "合规 / 超管", true));
            sections.put("leadership", new TrustSectionView("leadership", "管理团队", "人员卡 ×5", "v3", "published", "03-08", "内容主管", false));
            jurisdictions.put("MAS", new DisclosureJurisdictionView("MAS", "新加坡", "v11", "published", "05-02", 41_200, 100, 0));
            jurisdictions.put("SFC", new DisclosureJurisdictionView("SFC", "香港", "v12", "published", "06-08", 9_400, 72, 312));
            gates.put("withdraw", new DisclosureGateActionView("withdraw", "提现", "提交提现前服务器先验披露确认", "已实装", "ok", true));
            gates.put("staking", new DisclosureGateActionView("staking", "质押锁仓", "确认状态过期时拦截质押入口", "已实装", "warn", false));
            gates.put("nexv2", new DisclosureGateActionView("nexv2", "NEX v2 历史锁仓", "已下线历史兼容项，只读展示，不允许重新启用", "已下线 · 历史兼容", "dim", false));
            activeGateKeys.add("withdraw");
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public void ensureBaseGateActions(LocalDateTime now) {
            gates.putIfAbsent("withdraw", new DisclosureGateActionView("withdraw", "提现", "提交提现前服务器先验披露确认", "已实装", "ok", true));
            gates.putIfAbsent("staking", new DisclosureGateActionView("staking", "质押锁仓", "确认状态过期时拦截质押入口", "已实装", "warn", false));
            gates.putIfAbsent("exchange", new DisclosureGateActionView("exchange", "NEX 兑换", "兑换提交前服务器先验披露确认", "已实装", "warn", false));
            gates.putIfAbsent("nexv2", new DisclosureGateActionView("nexv2", "NEX v2 历史锁仓", "已下线历史兼容项，只读展示，不允许重新启用", "已下线 · 历史兼容", "dim", false));
        }

        @Override
        public List<TrustSectionView> listTrustSections() {
            return new ArrayList<>(sections.values());
        }

        @Override
        public Optional<TrustSectionView> findTrustSection(String sectionKey) {
            return Optional.ofNullable(sections.get(sectionKey));
        }

        @Override
        public List<TrustSectionFieldView> listSectionFields() {
            return List.of(
                    new TrustSectionFieldView("leadership", "成员数", "5 行高管卡"),
                    new TrustSectionFieldView("leadership", "字段", "姓名 / 职务 / 前公司 / LinkedIn 占位链接"));
        }

        @Override
        public List<FinancialFieldView> listFinancialFields() {
            return List.of(new FinancialFieldView("MRR", "$4.87M", "+22%"));
        }

        @Override
        public List<DisclosureJurisdictionView> listJurisdictions() {
            return new ArrayList<>(jurisdictions.values());
        }

        @Override
        public Optional<DisclosureJurisdictionView> findJurisdiction(String jurisdiction) {
            return Optional.ofNullable(jurisdictions.get(jurisdiction));
        }

        @Override
        public List<DisclosureChapterView> listChapters(String jurisdiction, String version) {
            return List.of(
                    new DisclosureChapterView(jurisdiction, version, "01", "收益预估不构成承诺", "Earnings estimates are not guarantees", "zh", "en"),
                    new DisclosureChapterView(jurisdiction, version, "02", "硬件衰减与产量波动", "Hardware decay & output variance", "zh", "en"));
        }

        @Override
        public List<DisclosureGateActionView> listGateActions() {
            return gates.values().stream()
                    .map(action -> new DisclosureGateActionView(
                            action.key(),
                            action.name(),
                            action.sub(),
                            action.status(),
                            action.tone(),
                            activeGateKeys.contains(action.key()) && !"nexv2".equals(action.key())))
                    .toList();
        }

        @Override
        public Optional<DisclosureDraftView> findLatestDraft() {
            return drafts.values().stream().reduce((first, second) -> second);
        }

        @Override
        public Optional<DisclosureDraftView> findDraft(String jurisdiction) {
            return drafts.values().stream()
                    .filter(draft -> draft.jurisdiction().equals(jurisdiction))
                    .reduce((first, second) -> second);
        }

        @Override
        public void updateTrustSection(String sectionKey, String version, String status, String operator, LocalDateTime now) {
            TrustSectionView current = sections.get(sectionKey);
            sections.put(sectionKey, new TrustSectionView(
                    current.key(),
                    current.desc(),
                    current.struct(),
                    version,
                    status.toLowerCase(),
                    "06-18",
                    current.roleGate(),
                    current.highSensitivity()));
        }

        @Override
        public void saveDisclosureDraft(DisclosureDraftRequest request, String status, LocalDateTime now) {
            drafts.put(request.jurisdiction() + "::" + request.version(), new DisclosureDraftView(
                    request.version(),
                    request.jurisdiction(),
                    request.languageScope(),
                    request.effectiveDate(),
                    Boolean.TRUE.equals(request.requiresReack()),
                    request.zh(),
                    request.en(),
                    status.toLowerCase()));
        }

        @Override
        public void publishDisclosure(String jurisdiction, DisclosureDraftRequest request, LocalDateTime now) {
            saveDisclosureDraft(request, "PUBLISHED", now);
            DisclosureJurisdictionView current = jurisdictions.get(jurisdiction);
            jurisdictions.put(jurisdiction, new DisclosureJurisdictionView(
                    current.code(),
                    current.name(),
                    request.version(),
                    "published",
                    "06-18",
                    current.affected(),
                    Boolean.TRUE.equals(request.requiresReack()) ? 0 : 100,
                    current.blocked()));
        }

        @Override
        public void updateGateScope(Set<String> activeKeys, String operator, LocalDateTime now) {
            activeGateKeys.clear();
            activeKeys.stream()
                    .filter(key -> !"nexv2".equals(key))
                    .forEach(activeGateKeys::add);
        }
    }
}
