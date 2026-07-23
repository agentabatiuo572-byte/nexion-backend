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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

class OpsSessionTemplateServiceTest {
    private final FakeSessionTemplateRepository templateRepository = new FakeSessionTemplateRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsSessionTemplateService service = new OpsSessionTemplateService(
            templateRepository,
            configFacade,
            auditLogService,
            Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC")),
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewUsesBackendSourcesAndConfigFacade() {
        configFacade.values.put("I.session.cat.support.enabled", "off");
        configFacade.values.put("I.session.advisor.policy.delayMs", "2500");
        configFacade.values.put("I.session.workbench.timeoutFallback", "on");

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(templateRepository.seedCalls).isZero();
        assertThat(result.getData().categories()).anySatisfy(category -> {
            assertThat(category.type()).isEqualTo("support");
            assertThat(category.enabled()).isFalse();
        });
        assertThat(result.getData().advisorPolicy().delayMs()).isEqualTo(2500);
        assertThat(result.getData().workbenchPolicy().timeoutFallback()).isTrue();
        assertThat(result.getData().statusOptions()).containsExactly("draft", "published", "archived");
        assertThat(result.getData().scripts()).extracting(SessionScriptView::id).contains("AS-001");
        assertThat(result.getData().sources()).contains("nx_config_item:content-session", "nx_help_article:session_script");
    }

    @Test
    void scriptsReturnPagedBackendRows() {
        templateRepository.scripts.add(new SessionScriptView("AS-002", "升级", "升级设备收益更稳。", "/store", "draft", "全量用户", now()));
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
                "暂停顾问会话入口"));

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
                "收紧顾问推送冷却时间"));

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
                "全量用户",
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
                "全量用户",
                "draft",
                "Marina K.",
                "新增设备升级顾问话术"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).startsWith("AS-");
        assertThat(result.getData().ctaPath()).isEqualTo("/store");
        assertThat(templateRepository.scripts).hasSize(2);
        assertAuditAction("M5_SESSION_SCRIPT_CREATED");
    }

    @Test
    void concurrentSameMillisecondScriptCreatesUseCollisionResistantIds() {
        var results = IntStream.range(0, 32).parallel()
                .mapToObj(index -> service.createScript("idem-m5-script-same-ms-" + index, new SessionScriptCreateRequest(
                        "开场", "同毫秒并发话术-" + index, "—", "全量用户", "draft", "Marina K.", "验证同毫秒并发创建话术唯一编号")))
                .toList();

        assertThat(results).allSatisfy(result -> assertThat(result.getCode()).isZero());
        assertThat(results).extracting(result -> result.getData().id()).doesNotHaveDuplicates();
    }

    @Test
    void newScriptAndReplyTemplateMustStartAsDraft() {
        var script = service.createScript("idem-m5-script-published", new SessionScriptCreateRequest(
                "升级",
                "这是一条不能绕过发布流程的话术。",
                "—",
                "全量用户",
                "published",
                "Marina K.",
                "验证新增话术必须先保存草稿"));
        var template = service.createReplyTemplate("idem-m5-template-archived", new SessionReplyTemplateCreateRequest(
                "support",
                "这是一条不能直接归档的回复模板。",
                "archived",
                "Marina K.",
                "验证新增模板必须先保存草稿"));

        assertThat(script.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(script.getMessage()).isEqualTo("SESSION_SCRIPT_INITIAL_STATUS_MUST_BE_DRAFT");
        assertThat(template.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(template.getMessage()).isEqualTo("SESSION_REPLY_TEMPLATE_INITIAL_STATUS_MUST_BE_DRAFT");
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
    void updateScriptAudiencePersistsBackendOwnedOption() {
        var result = service.updateScriptAudience("AS-001", "idem-m5-script-audience", new SessionScriptAudienceRequest(
                "新注册用户",
                "Marina K.",
                "圈定近期新注册用户范围"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().audience()).isEqualTo("新注册用户");
    }

    @Test
    void scriptsRejectAudienceOutsideBackendOwnedOptions() {
        var created = service.createScript("idem-m5-script-invalid-audience", new SessionScriptCreateRequest(
                "开场",
                "这是一条受众边界测试话术。",
                "—",
                "自由文本受众",
                "draft",
                "Marina K.",
                "验证新增话术受众必须来自权威选项"));
        var updated = service.updateScriptAudience("AS-001", "idem-m5-update-invalid-audience", new SessionScriptAudienceRequest(
                "自由文本受众",
                "Marina K.",
                "验证修改话术受众必须来自权威选项"));

        assertThat(created.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(updated.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(service.overview().getData().audienceOptions())
                .containsExactly("全量用户", "新注册用户", "高价值用户", "活跃设备用户");
    }

    @Test
    void createReplyTemplateAndStatusChangeWork() {
        var created = service.createReplyTemplate("idem-m5-template", new SessionReplyTemplateCreateRequest(
                "support",
                "收到,我先调出你的账户核对。",
                "draft",
                "Marina K.",
                "新增客服标准回复模板"));

        assertThat(created.getCode()).isZero();
        assertThat(created.getData().id()).startsWith("RT-");

        var status = service.updateReplyTemplateStatus(created.getData().id(), "idem-m5-template-status", new SessionReplyTemplateStatusRequest(
                "published",
                "Marina K.",
                "发布客服标准回复模板"));

        assertThat(status.getCode()).isZero();
        assertThat(status.getData().status()).isEqualTo("published");
    }

    @Test
    void concurrentSameMillisecondReplyTemplateCreatesUseCollisionResistantIds() {
        var results = IntStream.range(0, 32).parallel()
                .mapToObj(index -> service.createReplyTemplate("idem-m5-template-same-ms-" + index, new SessionReplyTemplateCreateRequest(
                        "support", "同毫秒并发回复模板-" + index, "draft", "Marina K.", "验证同毫秒并发创建模板唯一编号")))
                .toList();

        assertThat(results).allSatisfy(result -> assertThat(result.getCode()).isZero());
        assertThat(results).extracting(result -> result.getData().id()).doesNotHaveDuplicates();
    }

    @Test
    void overviewAlwaysProvidesBackendOwnedAudienceChoices() {
        templateRepository.scripts.clear();

        var result = service.overview();

        assertThat(result.getData().audienceOptions()).isNotEmpty();
        assertThat(result.getData().advisorPolicy().audience()).isIn(result.getData().audienceOptions());
    }

    @Test
    void advisorAudienceCanBeUpdatedFromBackendOwnedChoices() {
        var result = service.updateAdvisorPolicy("audience", "idem-m5-audience", new SessionAdvisorPolicyUpdateRequest(
                "新注册用户",
                "spoofed-user",
                "调整顾问推送受众范围"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().audience()).isEqualTo("新注册用户");
        assertThat(configFacade.values).containsEntry("I.session.advisor.policy.audience", "新注册用户");
    }

    @Test
    void commandReasonMustStayWithinEightToTwoHundredCharacters() {
        var tooShort = service.updateCategory("advisor", "idem-m5-short", new SessionCategoryToggleRequest(
                false,
                "spoofed-user",
                "1234567"));
        var tooLong = service.updateCategory("advisor", "idem-m5-long", new SessionCategoryToggleRequest(
                false,
                "spoofed-user",
                "x".repeat(201)));

        assertThat(tooShort.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(tooLong.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void publishedScriptAndTemplateArchiveWithoutFallingBackToDraft() {
        var script = service.updateScriptStatus("AS-001", "idem-m5-script-archive", new SessionScriptStatusRequest(
                "archived",
                "spoofed-user",
                "归档已发布顾问话术"));
        var template = service.updateReplyTemplateStatus("RT-S1", "idem-m5-template-archive", new SessionReplyTemplateStatusRequest(
                "archived",
                "spoofed-user",
                "归档已发布回复模板"));

        assertThat(script.getCode()).isZero();
        assertThat(script.getData().status()).isEqualTo("archived");
        assertThat(template.getCode()).isZero();
        assertThat(template.getData().status()).isEqualTo("archived");
    }

    @Test
    void staleExpectedValuesAreRejectedBeforeAnyBusinessWrite() {
        var category = service.updateCategory("advisor", "idem-m5-stale-category", new SessionCategoryToggleRequest(
                false,
                false,
                "spoofed-user",
                "基于过期页面暂停顾问入口"));
        var policy = service.updateAdvisorPolicy("cooldownHours", "idem-m5-stale-policy", new SessionAdvisorPolicyUpdateRequest(
                "48",
                "12",
                "spoofed-user",
                "基于过期页面调整冷却时间"));
        var script = service.updateScriptStatus("AS-001", "idem-m5-stale-script", new SessionScriptStatusRequest(
                "archived",
                "draft",
                "spoofed-user",
                "基于过期页面归档顾问话术"));

        assertThat(category.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(policy.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(script.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(configFacade.values).doesNotContainKeys(
                "I.session.cat.advisor.enabled",
                "I.session.advisor.policy.cooldownHours");
        assertThat(templateRepository.findScript("AS-001")).get().extracting(SessionScriptView::status).isEqualTo("published");
    }

    @Test
    void auditActorComesFromAuthenticatedContextInsteadOfRequestBody() {
        var authentication = new UsernamePasswordAuthenticationToken("77", "n/a", List.of());
        authentication.setDetails(Map.of("username", "trusted-superadmin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            service.updateCategory("advisor", "idem-m5-actor", new SessionCategoryToggleRequest(
                    false,
                    "spoofed-user",
                    "暂停顾问入口进行维护"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("trusted-superadmin");
    }

    @Test
    void everyM5BusinessMutationIsTransactional() throws Exception {
        assertThat(OpsSessionTemplateService.class
                .getMethod("updateCategory", String.class, String.class, SessionCategoryToggleRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("updateScriptStatus", String.class, String.class, SessionScriptStatusRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("updateReplyTemplateStatus", String.class, String.class, SessionReplyTemplateStatusRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("updateAdvisorPolicy", String.class, String.class, SessionAdvisorPolicyUpdateRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("createScript", String.class, SessionScriptCreateRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("updateScriptAudience", String.class, String.class, SessionScriptAudienceRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(OpsSessionTemplateService.class
                .getMethod("createReplyTemplate", String.class, SessionReplyTemplateCreateRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertAuditAction(String action) {
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
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
        private final List<SessionScriptView> scripts = new CopyOnWriteArrayList<>(List.of(new SessionScriptView(
                "AS-001",
                "开场",
                "你好,我是你的专属客服。",
                "—",
                "published",
                "全量用户",
                now())));
        private final List<SessionReplyTemplateView> templates = new CopyOnWriteArrayList<>(List.of(new SessionReplyTemplateView(
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
