package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.dto.NotificationCampaignActionRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.dto.NotificationCapUpdateRequest;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsNotificationCampaignServiceTest {
    private final FakeNotificationCampaignRepository repository = new FakeNotificationCampaignRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T08:30:00Z"), ZoneOffset.UTC);
    private final OpsNotificationCampaignService service = new OpsNotificationCampaignService(
            repository,
            auditLogService,
            clock,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewReturnsCampaignsCapsAndServerSources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().campaigns()).hasSize(3);
        assertThat(result.getData().capRules()).hasSize(4);
        assertThat(result.getData().stats().monthScheduled()).isEqualTo(1);
        assertThat(result.getData().sources()).contains("nx_notification_campaign", "nx_notification_cap_rule", "nx_notification");
        assertThat(repository.seedCalls).isEqualTo(1);
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
                "新增通知草稿"));

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
        var result = service.scheduleCampaign("CMP-2619", "idem-i3-schedule", action("06-20 10:00 排期"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("scheduled");
        assertThat(result.getData().schedule()).isEqualTo("06-20 10:00 排期");
    }

    @Test
    void sendNowScheduledMovesItToSending() {
        var result = service.sendNow("CMP-2618", "idem-i3-send", action(null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("sending");
        assertThat(result.getData().schedule()).isEqualTo("立即下发中");
        assertThat(repository.dispatchBizNos).containsExactly("i3:send:idem-i3-send");
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
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(request ->
                "I3_NOTIFICATION_CAP_CHANGED".equals(request.getAction())
                        && "NOTIFICATION_CAP".equals(request.getResourceType())));
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
                "新增通知草稿");
    }

    private static NotificationCampaignDraftRequest draftRequest() {
        return new NotificationCampaignDraftRequest(
                "7 月费率说明公告",
                "费率说明已补充链上成本解释。",
                "high",
                "全量",
                "06-21 09:00 排期",
                new BigDecimal("1200"),
                "Marina K.",
                "更新通知草稿");
    }

    private static NotificationCampaignActionRequest action(String schedule) {
        return new NotificationCampaignActionRequest(schedule, "Marina K.", "通知状态变更");
    }

    private static final class FakeNotificationCampaignRepository implements NotificationCampaignRepository {
        private final Map<String, NotificationCampaignRow> campaigns = new LinkedHashMap<>();
        private final Map<String, NotificationCapRuleView> caps = new LinkedHashMap<>();
        private final List<String> dispatchBizNos = new ArrayList<>();
        private int seedCalls;

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
        public NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, LocalDateTime now) {
            NotificationCampaignRow row = new NotificationCampaignRow(
                    campaignNo,
                    request.name(),
                    "system",
                    request.tier(),
                    request.audience(),
                    "-",
                    "draft",
                    "-",
                    "-",
                    "-",
                    request.title() + "\n" + request.content(),
                    request.title() + "\n" + request.content(),
                    "-",
                    request.budget());
            campaigns.put(campaignNo, row);
            return row;
        }

        @Override
        public void updateDraft(String campaignNo, NotificationCampaignDraftRequest request, LocalDateTime now) {
            NotificationCampaignRow current = campaigns.get(campaignNo);
            campaigns.put(campaignNo, new NotificationCampaignRow(
                    current.id(),
                    request.title(),
                    current.kind(),
                    request.tier(),
                    request.audience(),
                    current.reach(),
                    "draft",
                    request.schedule(),
                    current.sent(),
                    current.read(),
                    request.body(),
                    request.body(),
                    current.swipeTo(),
                    request.budget()));
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
                    current.budget()));
        }

        @Override
        public int dispatchCampaignNotification(String campaignNo, String bizNo, String trigger, String operator, LocalDateTime now) {
            dispatchBizNos.add(bizNo);
            updateStatus(campaignNo, "SENDING", trigger, operator, now);
            return 1;
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
                    budget);
        }
    }
}
