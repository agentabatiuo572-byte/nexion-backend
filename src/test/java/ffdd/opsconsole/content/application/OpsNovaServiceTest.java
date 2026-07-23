package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.TrustedNovaSocialEvent;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventStatusRequest;
import ffdd.opsconsole.content.dto.NovaSocialEventSyncRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsNovaServiceTest {
    private final FakeNovaRepository novaRepository = new FakeNovaRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AtomicReference<String> currentPhase = new AtomicReference<>("P3");
    private final OpsNovaService service = new OpsNovaService(
            novaRepository,
            auditLogService,
            currentPhase::get);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overviewReturnsEmptyWhenNoBackendConfigExists() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().channels()).isEmpty();
        assertThat(result.getData().templates()).isEmpty();
        assertThat(result.getData().stats().onlineChannels()).isZero();
        assertThat(result.getData().sources())
                .containsExactly(
                        "nx_nova_channel",
                        "nx_nova_template",
                        "nx_nova_social_distribution",
                        "nx_nova_social_event",
                        "nx_notification");
    }

    @Test
    void overviewDoesNotExposeStaticNovaSeedRows() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().channels()).isEmpty();
        assertThat(result.getData().templates()).isEmpty();
        assertThat(result.getData().socialDistribution()).isEmpty();
        assertThat(result.getData().eventDriven())
                .extracting("name")
                .containsExactly("risk-alert", "team_event", "staking_event", "market_event", "weekly-quest-refresh");
        assertThat(result.getData().stats().totalChannels()).isZero();
    }

    @Test
    void commandReasonMustContainEightToTwoHundredCharacters() {
        NovaChannelUpsertRequest seven = new NovaChannelUpsertRequest(
                "reasonMin", "Reason minimum", "周期扫描", "15 min", "7d",
                BigDecimal.ZERO, false, "forged", "1234567");
        NovaChannelUpsertRequest eight = new NovaChannelUpsertRequest(
                "reasonOk", "Reason accepted", "周期扫描", "15 min", "7d",
                BigDecimal.ZERO, false, "forged", "12345678");
        NovaChannelUpsertRequest twoHundredOne = new NovaChannelUpsertRequest(
                "reasonMax", "Reason maximum", "周期扫描", "15 min", "7d",
                BigDecimal.ZERO, false, "forged", "x".repeat(201));

        assertThat(service.createChannel("idem-i2-reason-min", seven).getMessage())
                .isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
        assertThat(service.createChannel("idem-i2-reason-ok", eight).getCode()).isZero();
        assertThat(service.createChannel("idem-i2-reason-max", twoHundredOne).getMessage())
                .isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
    }

    @Test
    void authenticatedAdministratorOverridesForgedRequestOperator() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("admin-subject", "n/a", List.of());
        authentication.setDetails(Map.of("username", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var result = service.createChannel("idem-i2-auth-actor", channelRequest("authActor"));

        assertThat(result.getCode()).isZero();
        assertThat(novaRepository.lastOperator).isEqualTo("superadmin");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void mutationFailsClosedWhenRequiredAuditCannotBePersisted() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService).recordRequired(org.mockito.ArgumentMatchers.any(AuditLogWriteRequest.class));

        assertThatThrownBy(() -> service.createChannel("idem-i2-audit-required", channelRequest("auditRequired")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void overviewStatsComeFromRepositoryBusinessAggregates() {
        novaRepository.stats.put("todayDelivered", 42L);
        novaRepository.stats.put("avgCtr", new BigDecimal("18.75"));
        novaRepository.stats.put("ctrTarget", 25);
        novaRepository.stats.put("onlineChannels", 2);
        novaRepository.stats.put("totalChannels", 3);
        novaRepository.stats.put("weeklySocial", 88L);

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().stats().todayDelivered()).isEqualTo("42");
        assertThat(result.getData().stats().ctr()).isEqualTo("18.8%");
        assertThat(result.getData().stats().ctrTarget()).isEqualTo(25);
        assertThat(result.getData().stats().onlineChannels()).isEqualTo(2);
        assertThat(result.getData().stats().totalChannels()).isEqualTo(3);
        assertThat(result.getData().stats().weeklySocial()).isEqualTo("88");
    }

    @Test
    void phaseKeyedCadenceFollowsTheH1AuthoritativePhaseReadOnly() {
        novaRepository.channels.put("tradein", new NovaChannelView(
                "tradein", "以旧换新提醒", "资格满足", "15 min", "60 min",
                "P1-P2 skip / P3-P4 60 min / P5-P6 24h", BigDecimal.ZERO, true));
        novaRepository.channels.put("taskLockMonthly", new NovaChannelView(
                "taskLockMonthly", "月度任务锁定", "任务锁定", "30 min", "30d",
                "P1-P2 30d / P3-P4 7d / P5-P6 84h", BigDecimal.ZERO, true));
        currentPhase.set("P5");

        var result = service.overview();

        assertThat(result.getData().channels()).filteredOn(channel -> "tradein".equals(channel.key()))
                .singleElement().satisfies(channel -> {
                    assertThat(channel.cooldown()).isEqualTo("24h");
                    assertThat(channel.phaseKeyed()).contains("H1 当前 P5");
                });
        assertThat(result.getData().channels()).filteredOn(channel -> "taskLockMonthly".equals(channel.key()))
                .singleElement().satisfies(channel -> {
                    assertThat(channel.cooldown()).isEqualTo("84h");
                    assertThat(channel.phaseKeyed()).contains("H1 当前 P5");
                });
    }

    @Test
    void phaseKeyedChannelCadenceCannotBeOverwrittenFromI2() {
        novaRepository.channels.put("tradein", new NovaChannelView(
                "tradein", "以旧换新提醒", "资格满足", "15 min", "60 min",
                "P1-P2 skip / P3-P4 60 min / P5-P6 24h", BigDecimal.ZERO, true));
        NovaChannelUpsertRequest request = new NovaChannelUpsertRequest(
                "tradein", "以旧换新提醒", "资格满足", "10 min", "20 min",
                BigDecimal.ZERO, true, "forged", "尝试从 I2 覆盖 H1 阶段节奏");

        var result = service.updateChannel("tradein", "idem-i2-h1-readonly", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_PHASE_CADENCE_H1_READ_ONLY");
    }

    @Test
    void createChannelRequiresIdempotencyKey() {
        var result = service.createChannel(null, channelRequest(null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void createChannelPersistsFieldKeysAndAudits() {
        var result = service.createChannel("idem-i2-channel", channelRequest("weeklyRecap"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().key()).isEqualTo("weeklyRecap");
        assertThat(novaRepository.channel("weeklyRecap")).get()
                .extracting(NovaChannelView::name, NovaChannelView::trigger, NovaChannelView::enabled)
                .containsExactly("Weekly recap", "每周任务完成后触发", false);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I2_NOVA_CHANNEL_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("NOVA");
    }

    @Test
    void updateChannelStatusRejectsSameStateWith409() {
        service.createChannel("idem-i2-channel-before-same-state", channelRequest("welcome"));

        var result = service.updateChannelStatus("welcome", "idem-i2-toggle", new NovaChannelStatusRequest(
                false,
                "Marina K.",
                "重复开启通道状态验证"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateChannelStatusPersistsKillState() {
        service.createChannel("idem-i2-channel-before-kill", channelRequest("welcome"));
        NovaChannelView created = novaRepository.channels.get("welcome");
        novaRepository.channels.put("welcome", new NovaChannelView(
                created.key(), created.name(), created.trigger(), created.tick(), created.cooldown(),
                created.phaseKeyed(), created.ctr(), true));

        var result = service.updateChannelStatus("welcome", "idem-i2-toggle", new NovaChannelStatusRequest(
                false,
                "Marina K.",
                "监管要求临时停推"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().enabled()).isFalse();
        assertThat(novaRepository.channel("welcome")).get()
                .extracting(NovaChannelView::enabled)
                .isEqualTo(false);
    }

    @Test
    void deleteChannelHidesItFromOverview() {
        service.createChannel("idem-i2-channel-before-delete", channelRequest("welcome"));

        var result = service.deleteChannel("welcome", "idem-i2-delete", new NovaDeleteRequest(
                "Marina K.",
                "移除重复 welcome 通道"));

        assertThat(result.getCode()).isZero();
        assertThat(service.overview().getData().channels()).noneMatch(channel -> "welcome".equals(channel.key()));
    }

    @Test
    void createTemplateAddsDraftTemplate() {
        service.createChannel("idem-i2-weekly-channel", channelRequest("weeklyRecap"));
        var result = service.createTemplate("idem-i2-template", new NovaTemplateCreateRequest(
                "weeklyRecap",
                "每周回顾",
                "/me/weekly",
                "v1",
                "每周回顾", "你本周获得 {amount} NEX",
                "Tổng kết tuần", "Bạn đã nhận {amount} NEX tuần này",
                "", "",
                "Marina K.",
                "新增每周回顾模板"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("DRAFT");
        assertThat(novaRepository.template("weeklyRecap")).get()
                .extracting(NovaTemplateView::status)
                .isEqualTo("DRAFT");
    }

    @Test
    void createChannelRejectsFreeTextOrReversedCadence() {
        NovaChannelUpsertRequest request = new NovaChannelUpsertRequest(
                "badCadence", "错误节奏", "周期扫描", "每 25 个任务", "10 min",
                BigDecimal.ZERO, false, "Marina K.", "验证结构化节奏字段");

        var result = service.createChannel("idem-i2-bad-cadence", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_CHANNEL_CADENCE_INVALID");
    }

    @Test
    void createChannelStartsDisabledUntilPublishedTemplateExists() {
        var result = service.createChannel("idem-i2-safe-channel", channelRequest("safeChannel"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().enabled()).isFalse();
    }

    @Test
    void enablingChannelRequiresPublishedLocalizedTemplate() {
        service.createChannel("idem-i2-gated-channel", channelRequest("gatedChannel"));

        var result = service.updateChannelStatus("gatedChannel", "idem-i2-enable-without-template",
                new NovaChannelStatusRequest(true, "Marina K.", "尝试启用未配置模板通道"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_PUBLISHED_TEMPLATE_REQUIRED");
    }

    @Test
    void templateRejectsMismatchedLocalizedPlaceholders() {
        service.createChannel("idem-i2-placeholder-channel", channelRequest("placeholderChannel"));

        var result = service.createTemplate("idem-i2-placeholder-template", new NovaTemplateCreateRequest(
                "placeholderChannel", "占位符模板", "/earn", "v1",
                "到账提醒", "到账 {amount} NEX", "Thông báo", "Đã nhận {value} NEX", "", "",
                "Marina K.", "校验中越占位符一致性"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_TEMPLATE_PLACEHOLDERS_MISMATCH");
    }

    @Test
    void updateTemplateStatusPersistsTransition() {
        service.createChannel("idem-i2-market-channel", channelRequest("market"));
        service.createTemplate("idem-i2-template-before-archive", new NovaTemplateCreateRequest(
                "market",
                "行情模板",
                "/earn",
                "v1",
                "市场动态", "市场价格为 {amount}",
                "Thị trường", "Giá thị trường là {amount}",
                "", "",
                "Marina K.",
                "新增行情推送模板"));

        var result = service.updateTemplateStatus("market", "idem-i2-publish", new NovaTemplateStatusRequest(
                "ARCHIVED",
                "Marina K.",
                "归档旧行情推送模板"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("ARCHIVED");
        assertThat(novaRepository.template("market")).get()
                .extracting(NovaTemplateView::status)
                .isEqualTo("ARCHIVED");
    }

    @Test
    void updateDistributionRequiresTotal100() {
        var result = service.updateDistribution("idem-i2-dist", new NovaDistributionUpdateRequest(
                List.of(
                        new NovaDistributionUpdateRequest.Item("withdrawal", 30),
                        new NovaDistributionUpdateRequest.Item("vrank", 20)),
                "Marina K.",
                "调整 social 分布"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void updateDistributionRejectsUnsupportedKeyWith422() {
        var result = service.updateDistribution("idem-i2-dist", new NovaDistributionUpdateRequest(
                List.of(
                        new NovaDistributionUpdateRequest.Item("unknown", 100)),
                "Marina K.",
                "调整 social 分布"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_SOCIAL_DISTRIBUTION_KEY_INVALID");
    }

    @Test
    void updateDistributionPersistsPercentages() {
        var result = service.updateDistribution("idem-i2-dist", new NovaDistributionUpdateRequest(
                List.of(
                        new NovaDistributionUpdateRequest.Item("withdrawal", 25),
                        new NovaDistributionUpdateRequest.Item("vrank", 25),
                        new NovaDistributionUpdateRequest.Item("genesis", 20),
                        new NovaDistributionUpdateRequest.Item("aiClient", 20),
                        new NovaDistributionUpdateRequest.Item("newUsers", 10)),
                "Marina K.",
                "平衡 social 分布"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).extracting("pct").containsExactly(25, 25, 20, 20, 10);
        assertThat(novaRepository.distribution).anySatisfy(item ->
                assertThat(item).extracting(NovaSocialDistributionItem::key, NovaSocialDistributionItem::pct)
                        .containsExactly("aiClient", 20));
    }

    @Test
    void createSocialEventMasksSensitiveDisplayFieldsAndAudits() {
        LocalDateTime occurredAt = LocalDateTime.now().minusHours(1);
        var result = service.ingestTrustedSocialEvent("idem-social-create", new TrustedNovaSocialEvent(
                        "withdrawal", "withdrawal-90001", "NEXION_CORE", "nx_withdrawal_order",
                        "Nguyen Van An", "Ho Chi Minh City", new BigDecimal("18420"), "NEX",
                        "Withdrawal settled", occurredAt), occurredAt.plusHours(12),
                "Marina K.", "接入真实提现到账事件");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().actorDisplay()).isEqualTo("N***");
        assertThat(result.getData().cityDisplay()).isEqualTo("H***");
        assertThat(result.getData().amountDisplay()).isEqualTo("10K–50K NEX");
        assertThat(result.getData().sourceEventId()).startsWith("evt_").doesNotContain("90001");
        assertThat(result.getData().sourceTable()).isEqualTo("nx_withdrawal_order");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I2_NOVA_SOCIAL_EVENT_CREATED");
    }

    @Test
    void createSocialEventIsIdempotentBySourceEventId() {
        TrustedNovaSocialEvent request = socialEventRequest("withdrawal", "source-1");

        var first = service.ingestTrustedSocialEvent("idem-create-first", request, LocalDateTime.now().plusHours(6),
                "Marina K.", "同步真实业务事件来源");
        var replay = service.ingestTrustedSocialEvent("idem-create-replay", request, LocalDateTime.now().plusHours(6),
                "Marina K.", "同步真实业务事件来源");

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData().id()).isEqualTo(first.getData().id());
        assertThat(novaRepository.socialEvents).hasSize(1);
    }

    @Test
    void newUserEventUsesPrivacyThresholdBandRatherThanMoneyBand() {
        LocalDateTime occurredAt = LocalDateTime.now().minusHours(1);
        TrustedNovaSocialEvent event = new TrustedNovaSocialEvent(
                "newUsers", "newUsers:2026071210", "NEXION_CORE", "nx_user",
                "", "全网", new BigDecimal("42"), "人", "完整小时注册用户聚合", occurredAt);

        var result = service.ingestTrustedSocialEvent("idem-new-users-band", event, occurredAt.plusHours(12),
                "system", "同步完整小时聚合用户事件");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().amountDisplay()).isEqualTo("10–49 人");
    }

    @Test
    void sourceDeduplicationUsesTypeSystemAndSourceIdComposite() {
        TrustedNovaSocialEvent withdrawal = socialEventRequest("withdrawal", "shared-1");
        TrustedNovaSocialEvent genesis = new TrustedNovaSocialEvent(
                "genesis", "shared-1", "NEXION_CORE", "nx_genesis_order", "An", "HCMC",
                new BigDecimal("4200"), "USDT", "SERIES-A", LocalDateTime.now().minusMinutes(1));

        assertThat(service.ingestTrustedSocialEvent("idem-composite-w", withdrawal, LocalDateTime.now().plusHours(2),
                "Marina K.", "同步真实提现业务事件").getCode()).isZero();
        assertThat(service.ingestTrustedSocialEvent("idem-composite-g", genesis, LocalDateTime.now().plusHours(2),
                "Marina K.", "同步真实成交业务事件").getCode()).isZero();

        assertThat(novaRepository.socialEvents).hasSize(2);
    }

    @Test
    void createSocialEventRejectsUnknownSourceType() {
        var result = service.ingestTrustedSocialEvent("idem-bad-source", socialEventRequest("fabricated", "source-bad"),
                LocalDateTime.now().plusHours(6), "Marina K.", "同步真实业务事件来源");

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_SOCIAL_EVENT_TYPE_UNSUPPORTED");
    }

    @Test
    void listSocialEventsMarksExpiredRowsAndCanFilter() {
        NovaSocialEventView active = novaRepository.addEvent("withdrawal", "active-1", "A***", "H***", "1K–5K NEX",
                "ACTIVE", LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
        novaRepository.addEvent("vrank", "expired-1", "B***", "D***", "",
                "ACTIVE", LocalDateTime.now().minusDays(2), LocalDateTime.now().minusHours(1));

        var result = service.socialEvents("withdrawal", "ACTIVE");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(active.id());
            assertThat(event.sourceEventId()).startsWith("evt_");
        });
        assertThat(novaRepository.socialEvents).anyMatch(event -> "EXPIRED".equals(event.status()));
    }

    @Test
    void statusAndDeleteCommandsAreReplaySafe() {
        NovaSocialEventView created = novaRepository.addEvent("genesis", "genesis-1", "N***", "H***", "",
                "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        NovaSocialEventStatusRequest disable = new NovaSocialEventStatusRequest("DISABLED", "Marina K.", "停止异常成交事件");

        assertThat(service.updateSocialEventStatus(created.id(), "idem-disable-1", disable).getCode()).isZero();
        assertThat(service.updateSocialEventStatus(created.id(), "idem-disable-2", disable).getCode()).isZero();
        NovaDeleteRequest delete = new NovaDeleteRequest("Marina K.", "删除错误来源事件");
        assertThat(service.deleteSocialEvent(created.id(), "idem-delete-1", delete).getCode()).isZero();
        assertThat(service.deleteSocialEvent(created.id(), "idem-delete-2", delete).getCode()).isZero();

        NovaSocialEventView expiring = novaRepository.addEvent("withdrawal", "withdrawal-expire-now", "A***", "H***", "1K–5K NEX",
                "ACTIVE", LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        NovaSocialEventStatusRequest expire = new NovaSocialEventStatusRequest("EXPIRED", "Marina K.", "运营确认立即过期");
        assertThat(service.updateSocialEventStatus(expiring.id(), "idem-expire-now", expire).getCode()).isZero();
        assertThat(novaRepository.socialEvent(expiring.id())).get().extracting(NovaSocialEventView::status).isEqualTo("EXPIRED");
        NovaSocialEventStatusRequest restoreExpired = new NovaSocialEventStatusRequest("ACTIVE", "Marina K.", "尝试恢复已经过期事件");
        assertThat(service.updateSocialEventStatus(expiring.id(), "idem-restore-expired", restoreExpired).getMessage())
                .isEqualTo("NOVA_SOCIAL_EVENT_EXPIRED_IS_TERMINAL");
    }

    @Test
    void sampleUsesConfiguredProbabilityAndNeverFabricatesFallback() {
        novaRepository.channels.put("social", new NovaChannelView(
                "social", "全网真实动态", "真实事件触发", "20 min", "30 min", "", BigDecimal.ZERO, true));
        novaRepository.templates.put("social", new NovaTemplateView(
                "social", "真实动态模板", "NONE", "v1",
                "Nexion 真实动态", "{actor} 在 {city} 完成 {amount}",
                "Hoạt động thực", "{actor} tại {city} hoàn tất {amount}",
                "Verified activity", "{actor} in {city} completed {amount}", "PUBLISHED"));
        novaRepository.distribution.add(new NovaSocialDistributionItem("withdrawal", "提现到账", 100, "red"));

        var empty = service.sampleSocialEvent("ZH");
        assertThat(empty.getCode()).isZero();
        assertThat(empty.getData()).isNull();

        novaRepository.addEvent("withdrawal", "withdrawal-sample", "N***", "H***", "10K–50K NEX",
                "ACTIVE", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        var sample = service.sampleSocialEvent("VI");

        assertThat(sample.getCode()).isZero();
        assertThat(sample.getData().sourceEventId()).startsWith("evt_").doesNotContain("withdrawal-sample");
        assertThat(sample.getData().language()).isEqualTo("VI");
        assertThat(sample.getData().body()).contains("N***", "10K–50K NEX");
    }

    @Test
    void templateRejectsUnsupportedPlaceholderEvenWhenLanguagesMatch() {
        service.createChannel("idem-social-channel", channelRequest("social"));

        var result = service.createTemplate("idem-unsupported-placeholder", new NovaTemplateCreateRequest(
                "social", "真实动态模板", "NONE", "v1",
                "动态", "{foo} 已完成", "Hoạt động", "{foo} đã hoàn tất",
                "Activity", "{foo} completed", "Marina K.", "创建真实动态模板"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("NOVA_TEMPLATE_PLACEHOLDER_UNSUPPORTED");
    }

    @Test
    void syncReadsOnlyTrustedSourcesAndMarksMissingAiBillingUnavailable() {
        novaRepository.trustedEvents.put("withdrawal", List.of(socialEventRequest("withdrawal", "wd-sync-1")));

        var result = service.syncSocialEvents("idem-sync", new NovaSocialEventSyncRequest(
                List.of("withdrawal", "aiClient"), 24, 12, "Marina K.", "同步已验证真实业务来源"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().inserted()).isEqualTo(1);
        assertThat(result.getData().sources()).anySatisfy(source -> {
            assertThat(source.sourceType()).isEqualTo("aiClient");
            assertThat(source.status()).isEqualTo("UNAVAILABLE");
            assertThat(source.inserted()).isZero();
        });
    }

    private static TrustedNovaSocialEvent socialEventRequest(String type, String sourceEventId) {
        LocalDateTime occurredAt = LocalDateTime.now().minusMinutes(1);
        return new TrustedNovaSocialEvent(type, sourceEventId, "NEXION_CORE", "nx_withdrawal_order",
                "Nguyen Van An", "Ho Chi Minh City", new BigDecimal("4200"), "NEX", "Verified source", occurredAt);
    }

    private static NovaChannelUpsertRequest channelRequest(String key) {
        return new NovaChannelUpsertRequest(
                key,
                "Weekly recap",
                "每周任务完成后触发",
                "15 min",
                "7d",
                new BigDecimal("12.5"),
                true,
                "Marina K.",
                "新增周报推送通道");
    }

    private static final class FakeNovaRepository implements NovaRepository {
        private final Map<String, NovaChannelView> channels = new LinkedHashMap<>();
        private final Map<String, NovaTemplateView> templates = new LinkedHashMap<>();
        private final List<NovaSocialDistributionItem> distribution = new ArrayList<>();
        private final Map<String, NovaSocialPoolView> pools = new LinkedHashMap<>();
        private final Map<String, Object> stats = new LinkedHashMap<>();
        private final List<NovaSocialEventView> socialEvents = new ArrayList<>();
        private String lastOperator;
        private long nextSocialEventId = 1;
        private final Map<String, List<TrustedNovaSocialEvent>> trustedEvents = new LinkedHashMap<>();

        @Override
        public void ensureTables() {
        }

        @Override
        public List<NovaChannelView> channels() {
            return List.copyOf(channels.values());
        }

        @Override
        public Map<String, Object> stats() {
            return Map.copyOf(stats);
        }

        @Override
        public Optional<NovaChannelView> channel(String key) {
            return Optional.ofNullable(channels.get(key));
        }

        @Override
        public int nextChannelOrder() {
            return channels.size() * 10 + 10;
        }

        @Override
        public void createChannel(String key, String name, String trigger, String tick, String cooldown,
                                  BigDecimal ctr, boolean enabled, int sortOrder, String operator, String reason) {
            lastOperator = operator;
            channels.put(key, new NovaChannelView(key, name, trigger, tick, cooldown, "", ctr, enabled));
        }

        @Override
        public void updateChannel(String key, String name, String trigger, String tick, String cooldown,
                                  BigDecimal ctr, boolean enabled, String operator, String reason) {
            lastOperator = operator;
            channels.put(key, new NovaChannelView(key, name, trigger, tick, cooldown, "", ctr, enabled));
        }

        @Override
        public void updateChannelStatus(String key, boolean enabled, String operator, String reason) {
            lastOperator = operator;
            NovaChannelView current = channels.get(key);
            channels.put(key, new NovaChannelView(
                    current.key(), current.name(), current.trigger(), current.tick(), current.cooldown(),
                    current.phaseKeyed(), current.ctr(), enabled));
        }

        @Override
        public void deleteChannel(String key, String operator, String reason) {
            channels.remove(key);
        }

        @Override
        public List<NovaTemplateView> templates() {
            return List.copyOf(templates.values());
        }

        @Override
        public Optional<NovaTemplateView> template(String channel) {
            return Optional.ofNullable(templates.get(channel));
        }

        @Override
        public void createTemplate(String channel, String name, String cta, String version,
                                   String titleZh, String bodyZh, String titleVi, String bodyVi,
                                   String titleEn, String bodyEn, String operator, String reason) {
            templates.put(channel, new NovaTemplateView(channel, name, cta, version,
                    titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn, "DRAFT"));
        }

        @Override
        public void updateTemplate(String channel, String name, String cta, String version,
                                   String titleZh, String bodyZh, String titleVi, String bodyVi,
                                   String titleEn, String bodyEn, String operator, String reason) {
            templates.put(channel, new NovaTemplateView(channel, name, cta, version,
                    titleZh, bodyZh, titleVi, bodyVi, titleEn, bodyEn, "DRAFT"));
        }

        @Override
        public void updateTemplateStatus(String channel, String status, String operator, String reason) {
            NovaTemplateView current = templates.get(channel);
            templates.put(channel, new NovaTemplateView(current.channel(), current.name(), current.cta(), current.version(),
                    current.titleZh(), current.bodyZh(), current.titleVi(), current.bodyVi(), current.titleEn(), current.bodyEn(), status));
        }

        @Override
        public void deleteTemplate(String channel, String operator, String reason) {
            templates.remove(channel);
        }

        @Override
        public List<NovaSocialDistributionItem> socialDistribution() {
            return List.copyOf(distribution);
        }

        @Override
        public void upsertDistribution(String key, String name, int pct, String color, String operator, String reason) {
            distribution.removeIf(item -> item.key().equals(key));
            distribution.add(new NovaSocialDistributionItem(key, name, pct, color));
        }

        @Override
        public List<NovaSocialPoolView> socialPools() {
            return List.copyOf(pools.values());
        }

        @Override
        public Optional<NovaSocialPoolView> socialPool(String key) {
            return Optional.ofNullable(pools.get(key));
        }

        @Override
        public void upsertPool(String key, String name, String description, int count, String operator, String reason) {
            pools.put(key, new NovaSocialPoolView(key, name, description, count));
        }

        @Override
        public List<NovaSocialEventView> socialEvents() {
            return List.copyOf(socialEvents);
        }

        @Override
        public Optional<NovaSocialEventView> socialEvent(long id) {
            return socialEvents.stream().filter(event -> event.id() == id).findFirst();
        }

        @Override
        public Optional<NovaSocialEventView> socialEventBySource(String eventType, String sourceSystem, String sourceEventId) {
            return socialEvents.stream().filter(event -> event.eventType().equals(eventType)
                    && event.sourceSystem().equals(sourceSystem) && event.sourceEventId().equals(sourceEventId)).findFirst();
        }

        @Override
        public void createSocialEvent(TrustedNovaSocialEvent source, String actorDisplay, String cityDisplay,
                                      String amountDisplay, LocalDateTime expiresAt, String operator, String reason) {
            if (socialEventBySource(source.eventType(), source.sourceSystem(), source.sourceEventId()).isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                socialEvents.add(new NovaSocialEventView(nextSocialEventId++, source.eventType(), source.sourceEventId(),
                        actorDisplay, cityDisplay, amountDisplay, source.sourceNote(), source.sourceSystem(), source.sourceTable(),
                        "ACTIVE", source.occurredAt(), expiresAt, now, null, 0L, now, now));
            }
        }

        @Override
        public void updateSocialEventStatus(long id, String status, String operator, String reason) {
            socialEvent(id).ifPresent(current -> replaceEvent(current, status, current.lastDispatchedAt(), current.dispatchCount()));
        }

        @Override
        public void deleteSocialEvent(long id, String operator, String reason) {
            socialEvents.removeIf(event -> event.id() == id);
        }

        @Override
        public int expireSocialEvents(LocalDateTime now) {
            List<NovaSocialEventView> due = socialEvents.stream()
                    .filter(event -> "ACTIVE".equals(event.status()) && !event.expiresAt().isAfter(now)).toList();
            due.forEach(event -> replaceEvent(event, "EXPIRED", event.lastDispatchedAt(), event.dispatchCount()));
            return due.size();
        }

        @Override
        public List<NovaSocialEventView> activeSocialEvents(LocalDateTime now) {
            return socialEvents.stream()
                    .filter(event -> "ACTIVE".equals(event.status()) && event.expiresAt().isAfter(now)).toList();
        }

        @Override
        public List<TrustedNovaSocialEvent> trustedSourceEvents(String sourceType, LocalDateTime since, LocalDateTime until) {
            return trustedEvents.getOrDefault(sourceType, List.of());
        }

        @Override
        public void markSocialEventDispatched(long id, LocalDateTime dispatchedAt) {
            socialEvent(id).ifPresent(current -> replaceEvent(current, current.status(), dispatchedAt, current.dispatchCount() + 1));
        }

        private void replaceEvent(NovaSocialEventView current, String status, LocalDateTime lastDispatchedAt, long dispatchCount) {
            int index = socialEvents.indexOf(current);
            socialEvents.set(index, new NovaSocialEventView(current.id(), current.eventType(), current.sourceEventId(),
                    current.actorDisplay(), current.cityDisplay(), current.amountDisplay(), current.sourceNote(),
                    current.sourceSystem(), current.sourceTable(), status, current.occurredAt(), current.expiresAt(),
                    current.verifiedAt(), lastDispatchedAt, dispatchCount, current.createdAt(), LocalDateTime.now()));
        }

        NovaSocialEventView addEvent(String type, String sourceEventId, String actor, String city, String amount,
                                     String status, LocalDateTime occurredAt, LocalDateTime expiresAt) {
            NovaSocialEventView event = new NovaSocialEventView(nextSocialEventId++, type, sourceEventId, actor, city,
                    amount, "Verified", "NEXION_CORE", sourceTable(type), status, occurredAt, expiresAt,
                    occurredAt, null, 0L, occurredAt, occurredAt);
            socialEvents.add(event);
            return event;
        }

        private String sourceTable(String type) {
            return switch (type) {
                case "withdrawal" -> "nx_withdrawal_order";
                case "vrank" -> "nx_user_level_log";
                case "genesis" -> "nx_genesis_order";
                case "newUsers" -> "nx_user";
                default -> "";
            };
        }
    }
}
