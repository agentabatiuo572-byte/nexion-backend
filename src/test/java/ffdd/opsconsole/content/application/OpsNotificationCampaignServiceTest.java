package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.dto.NotificationAudienceEstimateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

class OpsNotificationCampaignServiceTest {
    private final FakeNotificationCampaignRepository repository = new FakeNotificationCampaignRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T08:30:00Z"), ZoneOffset.UTC);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private final NotificationCampaignDispatchExecutor dispatchExecutor =
            new NotificationCampaignDispatchExecutor(repository, auditLogService);
    private final OpsNotificationCampaignService service = new OpsNotificationCampaignService(
            repository,
            auditLogService,
            clock,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            lockMapper,
            configFacade,
            dispatchExecutor);

    @BeforeEach
    void stubLockGuard() {
        // A2 锁守卫默认放行:countActiveByTarget=0 表示无活跃锁,updateCapRule 直通
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(configFacade.activeValue("growth.phase.current")).thenReturn(Optional.of("P3"));
        repository.estimatedAudience = 1;
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewReturnsCampaignsCapsAndServerSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().campaigns()).hasSize(3);
        assertThat(result.getData().capRules()).hasSize(4);
        assertThat(result.getData().stats().monthScheduled()).isEqualTo(1);
        assertThat(result.getData().sources()).contains("nx_notification_campaign", "nx_notification_cap_rule", "nx_notification");
        assertThat(result.getData().deliveryCatalog().kinds())
                .extracting("value")
                .contains("system", "commission", "team", "staking", "market", "genesis");
        assertThat(repository.seedCalls).isZero();
    }

    @Test
    void scheduleRejectsEmptyAudienceWith422() {
        repository.estimatedAudience = 0;

        var result = service.scheduleCampaign("CMP-2619", "idem-empty-audience", action("2026-06-20T10:00:00"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("AUDIENCE_EMPTY");
        assertThat(repository.campaigns.get("CMP-2619").status()).isEqualTo("draft");
    }

    @Test
    void createCampaignRequiresIdempotencyKey() {
        var result = service.createCampaign(null, createRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void createCampaignRejectsJsonBody() {
        var result = service.createCampaign("idem-i3-create", new NotificationCampaignCreateRequest(
                "JSON campaign",
                "JSON",
                "{\"raw\":true}",
                "normal",
                "全量",
                BigDecimal.TEN,
                "Marina K.",
                "新增系统通知草稿内容"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAMPAIGN_JSON_NOT_ALLOWED");
    }

    @Test
    void createCampaignPersistsDraftAndAudits() {
        var result = service.createCampaign("idem-i3-create", createRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).startsWith("CMP-N-july-fee-notice-20260618083000");
        assertThat(result.getData().status()).isEqualTo("draft");
        assertThat(repository.campaigns).containsKey(result.getData().id());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I3_NOTIFICATION_CAMPAIGN_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("NOTIFICATION_CAMPAIGN");
    }

    @Test
    void createCampaignTruncatesGeneratedCampaignNoSlug() {
        var result = service.createCampaign("idem-i3-create-long", new NotificationCampaignCreateRequest(
                "e2e-support1-202606302116-212944516-with-extra-cross-domain-routing-proof",
                "长名称通知",
                "完整测试前缀保留在标题和正文中。",
                "normal",
                "全量",
                BigDecimal.TEN,
                "Marina K.",
                "新增长名称通知草稿"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).hasSizeLessThanOrEqualTo(64);
        assertThat(result.getData().id()).startsWith("CMP-N-e2e-support1-202606302116");
    }

    @Test
    void scheduleDraftMovesItToScheduled() {
        var result = service.scheduleCampaign("CMP-2619", "idem-i3-schedule", action("2026-06-20T10:00:00"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("scheduled");
        assertThat(result.getData().schedule()).isEqualTo("2026-06-20T10:00:00");
    }

    @Test
    void scheduleRejectsPastServerTime() {
        var result = service.scheduleCampaign("CMP-2619", "idem-i3-schedule-past", action("2026-06-18T08:29:59"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_SCHEDULE_MUST_BE_FUTURE");
    }

    @Test
    void scheduleRejectsWhenAuthoritativePlatformPhaseIsUnavailable() {
        when(configFacade.activeValue("growth.phase.current")).thenReturn(Optional.empty());

        var result = service.scheduleCampaign("CMP-2619", "idem-i3-schedule-no-phase", action("2026-06-20T10:00:00"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CURRENT_PHASE_UNAVAILABLE");
    }

    @Test
    void audienceEstimateUsesStructuredAndConditions() {
        repository.estimatedAudience = 18420;
        var result = service.estimateAudience(new NotificationAudienceEstimateRequest(
                new NotificationAudienceTarget("P2", "P5", "vi", 30)));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().estimatedUsers()).isEqualTo(18420);
        assertThat(repository.lastEstimateTarget).isEqualTo(new NotificationAudienceTarget("P2", "P5", "vi", 30));
        assertThat(repository.lastEstimatePhase).isEqualTo("P3");
    }

    @Test
    void audienceEstimateRejectsReversedPhaseRange() {
        var result = service.estimateAudience(new NotificationAudienceEstimateRequest(
                new NotificationAudienceTarget("P5", "P2", "vi", 30)));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_AUDIENCE_PHASE_RANGE_INVALID");
    }

    @Test
    void sendNowScheduledCompletesAsSent() {
        var result = service.sendNow("CMP-2618", "idem-i3-send", action(null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("sent");
        assertThat(result.getData().schedule()).isEqualTo("已进入用户通知流");
        assertThat(repository.dispatchBizNos).containsExactly("i3:send:idem-i3-send");
    }

    @Test
    void updateScheduledCampaignCannotResetItToDraft() {
        var result = service.updateDraft("CMP-2618", "idem-i3-draft-scheduled", draftRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void criticalSchedulingRequiresElevatedAuthority() {
        repository.campaigns.put("CMP-CRITICAL", FakeNotificationCampaignRepository.campaign(
                "CMP-CRITICAL", "关键公告", "critical", "draft", "-", "-", "-", null));

        var denied = service.scheduleCampaign("CMP-CRITICAL", "idem-critical-denied", action("2026-06-20T10:00:00"));
        assertThat(denied.getCode()).isEqualTo(403);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("content_i3_cap_adjust"))));
        var capOnlyDenied = service.scheduleCampaign("CMP-CRITICAL", "idem-critical-cap-denied", action("2026-06-20T10:00:00"));
        assertThat(capOnlyDenied.getCode()).isEqualTo(403);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("content_i3_critical_send"))));
        var allowed = service.scheduleCampaign("CMP-CRITICAL", "idem-critical-allowed", action("2026-06-20T10:00:00"));
        assertThat(allowed.getCode()).isZero();
    }

    @Test
    void highSensitivityReasonMustBeBetweenEightAndTwoHundredCharacters() {
        var tooShort = service.scheduleCampaign("CMP-2619", "idem-short-reason",
                new NotificationCampaignActionRequest("2026-06-20T10:00:00", "Marina K.", "太短了"));
        assertThat(tooShort.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());

        var tooLong = service.scheduleCampaign("CMP-2619", "idem-long-reason",
                new NotificationCampaignActionRequest("2026-06-20T10:00:00", "Marina K.", "a".repeat(201)));
        assertThat(tooLong.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void dueSchedulerClaimsDispatchesAndCompletesCampaign() {
        repository.dueCampaignNos.add("CMP-2618");
        repository.estimatedAudience = 1;

        int completed = service.dispatchDueScheduledCampaigns();

        assertThat(completed).isEqualTo(1);
        assertThat(repository.campaigns.get("CMP-2618").status()).isEqualTo("sent");
        assertThat(repository.dispatchBizNos).contains("i3:schedule:CMP-2618");
        assertThat(repository.recoverStaleCalls).isEqualTo(1);
    }

    @Test
    void cancelSentCampaignReturns409() {
        var result = service.cancelScheduled("CMP-2615", "idem-i3-cancel", action(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateDraftRejectsSentCampaign() {
        var result = service.updateDraft("CMP-2615", "idem-i3-draft", draftRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateDraftPersistsFields() {
        var result = service.updateDraft("CMP-2619", "idem-i3-draft", draftRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().name()).isEqualTo("7 月费率说明公告");
        assertThat(result.getData().tier()).isEqualTo("high");
        assertThat(result.getData().budget()).isEqualByComparingTo("1200");
        assertThat(result.getData().bodyZh()).contains("中文标题", "中文正文");
        assertThat(result.getData().bodyVi()).contains("Tiêu đề", "Nội dung");
        assertThat(result.getData().bodyEn()).contains("English title", "English body");
    }

    @Test
    void createCampaignRejectsMismatchedLocalizedPlaceholders() {
        var result = service.createCampaign("idem-i3-placeholder", new NotificationCampaignCreateRequest(
                "Placeholder campaign", "中文", "Tiếng Việt", "English",
                "您好 {name}", "Xin chào {user}", "Hello {name}", "normal",
                new NotificationAudienceTarget("P1", "P6", "all", 0), BigDecimal.TEN,
                "Marina K.", "校验多语言占位符"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAMPAIGN_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void deleteDraftSoftDeletesCampaign() {
        var result = service.deleteDraft("CMP-2619", "idem-i3-delete", action(null));

        assertThat(result.getCode()).isZero();
        assertThat(repository.campaigns).doesNotContainKey("CMP-2619");
    }

    @Test
    void deleteSentCampaignReturns409() {
        var result = service.deleteDraft("CMP-2615", "idem-i3-delete-sent", action(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateCriticalCapIsLocked() {
        var result = service.updateCapRule("critical", "idem-i3-cap", new NotificationCapUpdateRequest(
                "10 条",
                "Marina K.",
                "尝试调低 critical"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAP_LOCKED");
    }

    @Test
    void updateCapPersistsAndAudits() {
        var result = service.updateCapRule("normal", "idem-i3-cap", new NotificationCapUpdateRequest(
                "180 条",
                "Marina K.",
                "压降普通通知容量"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().cap()).isEqualTo("180 条");
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "I3_NOTIFICATION_CAP_CHANGED".equals(request.getAction())
                        && "NOTIFICATION_CAP".equals(request.getResourceType())));
    }

    @Test
    void updateCapBlockedByA2ObjectLockReturns409() {
        when(lockMapper.countActiveByTarget("I", "notification_cap", "high")).thenReturn(1);

        var result = service.updateCapRule("high", "idem-i3-cap-locked", new NotificationCapUpdateRequest(
                "30 条",
                "Marina K.",
                "锁定档位后调整通知容量"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("OBJECT_LOCKED_BY_A2");
    }

    @Test
    void updateCapPassesThroughWhenNoLock() {
        // 默认无锁(countActiveByTarget=0),normal 档直通并写入
        var result = service.updateCapRule("normal", "idem-i3-cap-nolock", new NotificationCapUpdateRequest(
                "150 条",
                "Marina K.",
                "无锁状态下调整通知容量"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().cap()).isEqualTo("150 条");
    }

    @Test
    void updateCapRejectsFreeTextInsteadOfSilentlyUsingFallback() {
        var result = service.updateCapRule("normal", "idem-i3-cap-text", new NotificationCapUpdateRequest(
                "很多条",
                "Marina K.",
                "验证容量必须使用明确数字"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAP_INVALID");
    }

    @Test
    void updateCapRejectsValuesOutsideSupportedRange() {
        var result = service.updateCapRule("normal", "idem-i3-cap-range", new NotificationCapUpdateRequest(
                "10001",
                "Marina K.",
                "验证容量不能超过系统上限"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAP_INVALID");
    }

    @Test
    void updateCapRejectsExponentNotation() {
        var result = service.updateCapRule("normal", "idem-i3-cap-exponent", new NotificationCapUpdateRequest(
                "1e2",
                "Marina K.",
                "验证容量不能使用指数格式"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOTIFICATION_CAP_INVALID");
    }

    @Test
    void updateCapAndRetentionRunInOneTransaction() throws NoSuchMethodException {
        var method = OpsNotificationCampaignService.class.getMethod(
                "updateCapRule", String.class, String.class, NotificationCapUpdateRequest.class);

        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static NotificationCampaignCreateRequest createRequest() {
        return new NotificationCampaignCreateRequest(
                "July fee notice",
                "7 月费率说明",
                "7 月提现费率将按链上成本动态调整。",
                "normal",
                "全量",
                new BigDecimal("500"),
                "Marina K.",
                "新增系统通知草稿内容");
    }

    private static NotificationCampaignDraftRequest draftRequest() {
        return new NotificationCampaignDraftRequest(
                "7 月费率说明公告",
                "中文标题",
                "Tiêu đề",
                "English title",
                "中文正文",
                "Nội dung",
                "English body",
                "high",
                new NotificationAudienceTarget("P1", "P6", "all", 0),
                new BigDecimal("1200"),
                "Marina K.",
                "更新系统通知草稿内容");
    }

    private static NotificationCampaignActionRequest action(String schedule) {
        return new NotificationCampaignActionRequest(schedule, "Marina K.", "执行通知状态变更操作");
    }

    private static final class FakeNotificationCampaignRepository implements NotificationCampaignRepository {
        private final Map<String, NotificationCampaignRow> campaigns = new LinkedHashMap<>();
        private final Map<String, NotificationCapRuleView> caps = new LinkedHashMap<>();
        private final List<String> dispatchBizNos = new ArrayList<>();
        private final List<String> dueCampaignNos = new ArrayList<>();
        private long estimatedAudience;
        private NotificationAudienceTarget lastEstimateTarget;
        private String lastEstimatePhase;
        private int seedCalls;
        private int recoverStaleCalls;

        private FakeNotificationCampaignRepository() {
            campaigns.put("CMP-2618", campaign("CMP-2618", "6/15 钱包维护窗口公告", "high", "scheduled", "06-15 02:00 排期", "-", "-", null));
            campaigns.put("CMP-2615", campaign("CMP-2615", "KYC 二级认证引导", "high", "sent", "06-08 已发", "12.4K", "9.1K", null));
            campaigns.put("CMP-2619", campaign("CMP-2619", "7 月费率说明公告(草稿)", "normal", "draft", "-", "-", "-", null));
            caps.put("critical", new NotificationCapRuleView("critical", "∞ 永不淘汰", "合规硬约束", true));
            caps.put("high", new NotificationCapRuleView("high", "50 条", "高优运营事件", false));
            caps.put("normal", new NotificationCapRuleView("normal", "200 条", "常规运营公告", false));
            caps.put("low", new NotificationCapRuleView("low", "30 条 · TTL 24-48h", "低优教程提示", false));
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public List<NotificationCampaignRow> listCampaigns() {
            return new ArrayList<>(campaigns.values());
        }

        @Override
        public Optional<NotificationCampaignRow> findCampaign(String campaignNo) {
            return Optional.ofNullable(campaigns.get(campaignNo));
        }

        @Override
        public NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, long estimatedAudience, LocalDateTime now) {
            NotificationCampaignRow row = new NotificationCampaignRow(
                    campaignNo,
                    request.name(),
                    "system",
                    request.tier(),
                    "结构化受众",
                    String.valueOf(estimatedAudience),
                    "draft",
                    "-",
                    "-",
                    "-",
                    request.titleEn() + "\n" + request.bodyEn(),
                    request.titleZh() + "\n" + request.bodyZh(),
                    request.titleVi() + "\n" + request.bodyVi(),
                    "-",
                    request.budget(),
                    request.audienceTarget());
            campaigns.put(campaignNo, row);
            return row;
        }

        @Override
        public void updateDraft(String campaignNo, NotificationCampaignDraftRequest request, long estimatedAudience, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            campaigns.put(campaignNo, new NotificationCampaignRow(
                    current.id(),
                    request.name(),
                    current.kind(),
                    request.tier(),
                    "结构化受众",
                    String.valueOf(estimatedAudience),
                    "draft",
                    current.schedule(),
                    current.sent(),
                    current.read(),
                    request.titleEn() + "\n" + request.bodyEn(),
                    request.titleZh() + "\n" + request.bodyZh(),
                    request.titleVi() + "\n" + request.bodyVi(),
                    current.swipeTo(),
                    request.budget(),
                    request.audienceTarget()));
        }

        @Override
        public long estimateAudience(NotificationAudienceTarget target, String currentPhase, LocalDateTime now) {
            lastEstimateTarget = target;
            lastEstimatePhase = currentPhase;
            return estimatedAudience;
        }

        @Override
        public boolean deleteDraft(String campaignNo, LocalDateTime now) {
            return campaigns.remove(campaignNo) != null;
        }

        @Override
        public void updateStatus(String campaignNo, String status, String schedule, String operator, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            campaigns.put(campaignNo, new NotificationCampaignRow(
                    current.id(),
                    current.name(),
                    current.kind(),
                    current.tier(),
                    current.audience(),
                    current.reach(),
                    status.toLowerCase(Locale.ROOT),
                    schedule,
                    current.sent(),
                    current.read(),
                    current.bodyEn(),
                    current.bodyZh(),
                    current.swipeTo(),
                    current.budget(),
                    current.audienceTarget()));
        }

        @Override
        public int dispatchCampaignNotification(String campaignNo, String bizNo, String currentPhase, String trigger, String operator, LocalDateTime now) {
            dispatchBizNos.add(bizNo);
            return 1;
        }

        @Override
        public List<String> listDueScheduledCampaignNos(LocalDateTime now, int limit) {
            return dueCampaignNos.stream().limit(limit).toList();
        }

        @Override
        public boolean claimScheduled(String campaignNo, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            if (current == null || !"scheduled".equals(current.status())) {
                return false;
            }
            updateStatus(campaignNo, "SENDING", current.schedule(), "system", now);
            return true;
        }

        @Override
        public boolean claimForImmediateDispatch(String campaignNo, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            if (current == null || !Set.of("draft", "scheduled").contains(current.status())) {
                return false;
            }
            updateStatus(campaignNo, "SENDING", "立即下发中", "system", now);
            return true;
        }

        @Override
        public boolean cancelScheduled(String campaignNo, String operator, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            if (current == null || !"scheduled".equals(current.status())) {
                return false;
            }
            updateStatus(campaignNo, "CANCELLED", "已取消", operator, now);
            return true;
        }

        @Override
        public void completeDispatch(String campaignNo, String status, int sentCount, String schedule, String operator, LocalDateTime now) {
            updateStatus(campaignNo, status, schedule, operator, now);
        }

        @Override
        public int recoverStaleSending(LocalDateTime staleBefore, LocalDateTime now) {
            recoverStaleCalls++;
            return 0;
        }

        @Override
        public void applyRetention(LocalDateTime now) {
        }

        @Override
        public void applyRetentionForUser(Long userId) {
        }

        @Override
        public AppNotificationPage pageUserNotifications(Long userId, Long cursorId, String priority, int limit) {
            return new AppNotificationPage(List.of(), null, 0);
        }

        @Override
        public boolean markNotificationRead(Long userId, Long notificationId) {
            return false;
        }

        @Override
        public int markAllNotificationsRead(Long userId) {
            return 0;
        }

        @Override
        public int clearReadNotifications(Long userId) {
            return 0;
        }

        @Override
        public List<NotificationCapRuleView> listCapRules() {
            return new ArrayList<>(caps.values());
        }

        @Override
        public Optional<NotificationCapRuleView> findCapRule(String tier) {
            return Optional.ofNullable(caps.get(tier));
        }

        @Override
        public void updateCapRule(String tier, String cap, String operator, LocalDateTime now) {
            NotificationCapRuleView current = caps.get(tier);
            caps.put(tier, new NotificationCapRuleView(tier, cap, current.policy(), current.locked()));
        }

        private static NotificationCampaignRow campaign(String id, String name, String tier, String status, String schedule, String sent, String read, BigDecimal budget) {
            return new NotificationCampaignRow(
                    id,
                    name,
                    "system",
                    tier,
                    "全量",
                    "182K",
                    status,
                    schedule,
                    sent,
                    read,
                    "Body EN",
                    "Body ZH",
                    "-",
                    budget,
                    new NotificationAudienceTarget("P1", "P6", "all", 0));
        }
    }
}
