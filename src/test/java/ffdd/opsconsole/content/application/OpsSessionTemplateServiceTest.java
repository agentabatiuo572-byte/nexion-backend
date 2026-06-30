package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.SessionReplyTemplateView;
import ffdd.opsconsole.content.domain.SessionScriptView;
import ffdd.opsconsole.content.domain.SessionTemplateRepository;
import ffdd.opsconsole.content.dto.SessionAdvisorPolicyUpdateRequest;
import ffdd.opsconsole.content.dto.SessionCategoryToggleRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateCreateRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateQueryRequest;
import ffdd.opsconsole.content.dto.SessionReplyTemplateStatusRequest;
import ffdd.opsconsole.content.dto.SessionScriptAudienceRequest;
import ffdd.opsconsole.content.dto.SessionScriptCreateRequest;
import ffdd.opsconsole.content.dto.SessionScriptQueryRequest;
import ffdd.opsconsole.content.dto.SessionScriptStatusRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsSessionTemplateServiceTest {
    private final FakeSessionTemplateRepository templateRepository = new FakeSessionTemplateRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsSessionTemplateService service = new OpsSessionTemplateService(
            templateRepository,
            configFacade,
            auditLogService,
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")));

    @Test
    void overviewUsesBackendSourcesAndConfigFacade() {
        configFacade.values.put("I.session.cat.support.enabled", "off");
        configFacade.values.put("I.session.advisor.policy.delayMs", "2500");
        configFacade.values.put("I.session.workbench.timeoutFallback", "on");

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(templateRepository.seedCalls).isGreaterThan(0);
        assertThat(result.getData().categories()).anySatisfy(category -> {
            assertThat(category.type()).isEqualTo("support");
            assertThat(category.enabled()).isFalse();
        });
        assertThat(result.getData().advisorPolicy().delayMs()).isEqualTo(2500);
        assertThat(result.getData().workbenchPolicy().timeoutFallback()).isTrue();
        assertThat(result.getData().statusOptions()).containsExactly("published", "draft");
        assertThat(result.getData().scripts()).extracting(SessionScriptView::id).contains("AS-001");
        assertThat(result.getData().sources()).contains("nx_config_item:content-session", "nx_help_article:session_script");
    }

    @Test
    void scriptsReturnPagedBackendRows() {
        templateRepository.scripts.add(new SessionScriptView("AS-002", "升级", "升级设备收益更稳。", "/store", "draft", "全量", now()));
        templateRepository.scripts.add(new SessionScriptView("AS-003", "复投", "需要我帮你看复投方案吗?", "/staking", "published", "P3 阶段活跃", now()));

        var result = service.scripts(new SessionScriptQueryRequest(null, null, 2L, 2L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(3);
        assertThat(result.getData().getPageNum()).isEqualTo(2);
        assertThat(result.getData().getPageSize()).isEqualTo(2);
        assertThat(result.getData().getRecords()).extracting(SessionScriptView::id).containsExactly("AS-003");
    }

    @Test
    void replyTemplatesReturnPagedBackendRows() {
        templateRepository.templates.add(new SessionReplyTemplateView("RT-A1", "advisor", "我给你看几个方案。", "published", now()));
        templateRepository.templates.add(new SessionReplyTemplateView("RT-S2", "support", "请提供订单号。", "draft", now()));

        var result = service.replyTemplates(new SessionReplyTemplateQueryRequest(null, null, null, 1L, 2L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(3);
        assertThat(result.getData().getPageNum()).isEqualTo(1);
        assertThat(result.getData().getPageSize()).isEqualTo(2);
        assertThat(result.getData().getRecords()).extracting(SessionReplyTemplateView::id).containsExactly("RT-S1", "RT-A1");
    }

    @Test
    void updateCategoryRejectsReadonlyAiCategory() {
        var result = service.updateCategory("ai", "idem-m5-cat", new SessionCategoryToggleRequest(
                false,
                "Marina K.",
                "停用 AI 类别"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateCategoryPersistsConfigAndAudits() {
        var result = service.updateCategory("advisor", "idem-m5-cat", new SessionCategoryToggleRequest(
                false,
                "Marina K.",
                "暂停顾问入口"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().enabled()).isFalse();
        assertThat(configFacade.values).containsEntry("I.session.cat.advisor.enabled", "off");
        assertAuditAction("M5_SESSION_CATEGORY_UPDATED");
    }

    @Test
    void policyRejectsFreeformAudience() {
        var result = service.updateAdvisorPolicy("audience", "idem-m5-policy", new SessionAdvisorPolicyUpdateRequest(
                "{\"segment\":\"all\"}",
                "Marina K.",
                "调整顾问推送受众"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void policyPersistsStructuredField() {
        var result = service.updateAdvisorPolicy("cooldownHours", "idem-m5-policy", new SessionAdvisorPolicyUpdateRequest(
                "48",
                "Marina K.",
                "收紧冷却时间"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().cooldownHours()).isEqualTo(48);
        assertThat(configFacade.values).containsEntry("I.session.advisor.policy.cooldownHours", "48");
        assertAuditAction("M5_SESSION_ADVISOR_POLICY_UPDATED");
    }

    @Test
    void workbenchPolicyPersistsBackendConfigAndAudits() {
        var result = service.updateWorkbenchPolicy("timeoutFallback", "idem-m3-workbench", new SessionAdvisorPolicyUpdateRequest(
                "on",
                "Marina K.",
                "开启超时自动回落备勤池"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().timeoutFallback()).isTrue();
        assertThat(configFacade.values).containsEntry("I.session.workbench.timeoutFallback", "on");
        assertAuditAction("M3_SESSION_WORKBENCH_POLICY_UPDATED");
    }

    @Test
    void workbenchPolicyRejectsUnsupportedField() {
        var result = service.updateWorkbenchPolicy("localOnly", "idem-m3-workbench", new SessionAdvisorPolicyUpdateRequest(
                "on",
                "Marina K.",
                "尝试写入非后端策略"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(configFacade.values).doesNotContainKey("I.session.workbench.localOnly");
    }

    @Test
    void workbenchPolicyRequiresIdempotencyAndReason() {
        var result = service.updateWorkbenchPolicy("timeoutFallback", "", new SessionAdvisorPolicyUpdateRequest(
                "on",
                "Marina K.",
                "开启超时自动回落备勤池"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void createScriptRejectsManualUrl() {
        var result = service.createScript("idem-m5-script", new SessionScriptCreateRequest(
                "升级",
                "点击 https://example.com",
                "https://example.com",
                "全量",
                "published",
                "Marina K.",
                "新增顾问话术"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void createScriptPersistsAndAudits() {
        var result = service.createScript("idem-m5-script", new SessionScriptCreateRequest(
                "升级",
                "你的设备近期有不少时段闲置,升级后能减少空窗。",
                "/store",
                "全量",
                "draft",
                "Marina K.",
                "新增升级话术"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).startsWith("AS-");
        assertThat(result.getData().ctaPath()).isEqualTo("/store");
        assertThat(templateRepository.scripts).hasSize(2);
        assertAuditAction("M5_SESSION_SCRIPT_CREATED");
    }

    @Test
    void updateScriptStatusRejectsSameState() {
        var result = service.updateScriptStatus("AS-001", "idem-m5-script-status", new SessionScriptStatusRequest(
                "published",
                "Marina K.",
                "重复发布已有话术"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateScriptAudiencePersistsDynamicOption() {
        var result = service.updateScriptAudience("AS-001", "idem-m5-script-audience", new SessionScriptAudienceRequest(
                "注册 ≤14 天",
                "Marina K.",
                "圈定新注册用户"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().audience()).isEqualTo("注册 ≤14 天");
    }

    @Test
    void createReplyTemplateAndStatusChangeWork() {
        var created = service.createReplyTemplate("idem-m5-template", new SessionReplyTemplateCreateRequest(
                "support",
                "收到,我先调出你的账户核对。",
                "draft",
                "Marina K.",
                "新增客服模板"));

        assertThat(created.getCode()).isZero();
        assertThat(created.getData().id()).startsWith("RT-");

        var status = service.updateReplyTemplateStatus(created.getData().id(), "idem-m5-template-status", new SessionReplyTemplateStatusRequest(
                "published",
                "Marina K.",
                "发布客服模板"));

        assertThat(status.getCode()).isZero();
        assertThat(status.getData().status()).isEqualTo("published");
    }

    private void assertAuditAction(String action) {
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(action);
        assertThat(captor.getValue().getResourceType()).isEqualTo("SESSION_TEMPLATE");
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 6, 18, 0, 0);
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            return Map.copyOf(values);
        }
    }

    private static final class FakeSessionTemplateRepository implements SessionTemplateRepository {
        private int seedCalls;
        private final List<SessionScriptView> scripts = new ArrayList<>(List.of(new SessionScriptView(
                "AS-001",
                "开场",
                "你好,我是你的专属顾问。",
                "—",
                "published",
                "全量",
                now())));
        private final List<SessionReplyTemplateView> templates = new ArrayList<>(List.of(new SessionReplyTemplateView(
                "RT-S1",
                "support",
                "好的,我先调出你的账户。",
                "published",
                now())));

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<SessionScriptView> listScripts() {
            return List.copyOf(scripts);
        }

        @Override
        public PageResult<SessionScriptView> pageScripts(SessionScriptQueryRequest request) {
            long pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            long pageSize = request == null || request.pageSize() == null ? 10 : request.pageSize();
            int from = (int) Math.min(scripts.size(), Math.max(0, (pageNum - 1) * pageSize));
            int to = (int) Math.min(scripts.size(), from + pageSize);
            return new PageResult<>(scripts.size(), pageNum, pageSize, List.copyOf(scripts.subList(from, to)));
        }

        @Override
        public Optional<SessionScriptView> findScript(String scriptId) {
            return scripts.stream().filter(row -> row.id().equals(scriptId)).findFirst();
        }

        @Override
        public SessionScriptView createScript(String scriptId, SessionScriptCreateRequest request, LocalDateTime now) {
            SessionScriptView created = new SessionScriptView(
                    scriptId,
                    request.scriptGroup(),
                    request.text(),
                    request.ctaPath(),
                    request.status(),
                    request.audience(),
                    now);
            scripts.add(0, created);
            return created;
        }

        @Override
        public void updateScriptStatus(String scriptId, String status, LocalDateTime now) {
            SessionScriptView current = findScript(scriptId).orElseThrow();
            replaceScript(new SessionScriptView(current.id(), current.scriptGroup(), current.text(), current.ctaPath(), status, current.audience(), now));
        }

        @Override
        public void updateScriptAudience(String scriptId, String audience, LocalDateTime now) {
            SessionScriptView current = findScript(scriptId).orElseThrow();
            replaceScript(new SessionScriptView(current.id(), current.scriptGroup(), current.text(), current.ctaPath(), current.status(), audience, now));
        }

        @Override
        public List<SessionReplyTemplateView> listReplyTemplates() {
            return List.copyOf(templates);
        }

        @Override
        public PageResult<SessionReplyTemplateView> pageReplyTemplates(SessionReplyTemplateQueryRequest request) {
            long pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            long pageSize = request == null || request.pageSize() == null ? 10 : request.pageSize();
            int from = (int) Math.min(templates.size(), Math.max(0, (pageNum - 1) * pageSize));
            int to = (int) Math.min(templates.size(), from + pageSize);
            return new PageResult<>(templates.size(), pageNum, pageSize, List.copyOf(templates.subList(from, to)));
        }

        @Override
        public Optional<SessionReplyTemplateView> findReplyTemplate(String templateId) {
            return templates.stream().filter(row -> row.id().equals(templateId)).findFirst();
        }

        @Override
        public SessionReplyTemplateView createReplyTemplate(String templateId, SessionReplyTemplateCreateRequest request, LocalDateTime now) {
            SessionReplyTemplateView created = new SessionReplyTemplateView(templateId, request.type(), request.text(), request.status(), now);
            templates.add(0, created);
            return created;
        }

        @Override
        public void updateReplyTemplateStatus(String templateId, String status, LocalDateTime now) {
            SessionReplyTemplateView current = findReplyTemplate(templateId).orElseThrow();
            replaceTemplate(new SessionReplyTemplateView(current.id(), current.type(), current.text(), status, now));
        }

        private void replaceScript(SessionScriptView updated) {
            for (int index = 0; index < scripts.size(); index += 1) {
                if (scripts.get(index).id().equals(updated.id())) {
                    scripts.set(index, updated);
                    return;
                }
            }
        }

        private void replaceTemplate(SessionReplyTemplateView updated) {
            for (int index = 0; index < templates.size(); index += 1) {
                if (templates.get(index).id().equals(updated.id())) {
                    templates.set(index, updated);
                    return;
                }
            }
        }
    }
}
