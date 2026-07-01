package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaPoolUpdateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsNovaServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsNovaService service = new OpsNovaService(
            configFacade,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewReturnsEmptyWhenNoBackendConfigExists() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().channels()).isEmpty();
        assertThat(result.getData().templates()).isEmpty();
        assertThat(result.getData().stats().onlineChannels()).isZero();
        assertThat(result.getData().sources()).contains("nx_config_item");
    }

    @Test
    void disabledReadTimeSeedsDoNotExposeNovaSeedRows() {
        OpsNovaService realOnlyService = new OpsNovaService(
                new FakePlatformConfigFacade(),
                auditLogService,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        var result = realOnlyService.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().channels()).isEmpty();
        assertThat(result.getData().templates()).isEmpty();
        assertThat(result.getData().socialDistribution()).isEmpty();
        assertThat(result.getData().socialPools()).isEmpty();
        assertThat(result.getData().eventDriven()).isEmpty();
        assertThat(result.getData().stats().totalChannels()).isZero();
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
        assertThat(configFacade.values).containsEntry("nova.channel.weeklyRecap.name", "Weekly recap");
        assertThat(configFacade.values).containsEntry("nova.channel.weeklyRecap.trigger", "每周任务完成后触发");
        assertThat(configFacade.values).containsEntry("nova.channel.weeklyRecap.on", "true");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I2_NOVA_CHANNEL_CREATED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("NOVA");
    }

    @Test
    void updateChannelStatusRejectsSameStateWith409() {
        service.createChannel("idem-i2-channel-before-same-state", channelRequest("welcome"));

        var result = service.updateChannelStatus("welcome", "idem-i2-toggle", new NovaChannelStatusRequest(
                true,
                "Marina K.",
                "重复开启通道"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateChannelStatusPersistsKillState() {
        service.createChannel("idem-i2-channel-before-kill", channelRequest("welcome"));

        var result = service.updateChannelStatus("welcome", "idem-i2-toggle", new NovaChannelStatusRequest(
                false,
                "Marina K.",
                "监管要求临时停推"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().enabled()).isFalse();
        assertThat(configFacade.values).containsEntry("nova.channel.welcome.on", "false");
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
        var result = service.createTemplate("idem-i2-template", new NovaTemplateCreateRequest(
                "weeklyRecap",
                "每周回顾",
                "→ /me/weekly",
                "v1",
                "Marina K.",
                "新增每周回顾模板"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("DRAFT");
        assertThat(configFacade.values).containsEntry("nova.template.weeklyRecap.status", "DRAFT");
    }

    @Test
    void updateTemplateStatusPersistsTransition() {
        service.createTemplate("idem-i2-template-before-archive", new NovaTemplateCreateRequest(
                "market",
                "行情模板",
                "-> /market",
                "v1",
                "Marina K.",
                "新增行情模板"));

        var result = service.updateTemplateStatus("market", "idem-i2-publish", new NovaTemplateStatusRequest(
                "ARCHIVED",
                "Marina K.",
                "归档旧行情模板"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("ARCHIVED");
        assertThat(configFacade.values).containsEntry("nova.template.market.status", "ARCHIVED");
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
        assertThat(configFacade.values).containsEntry("nova.social.dist.aiClient.pct", "20");
    }

    @Test
    void updatePoolPersistsCount() {
        var result = service.updatePool("CITIES", "idem-i2-pool", new NovaPoolUpdateRequest(
                40,
                "Marina K.",
                "新增城市池条目"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().count()).isEqualTo(40);
        assertThat(configFacade.values).containsEntry("nova.social.pool.CITIES.count", "40");
    }

    private static NovaChannelUpsertRequest channelRequest(String key) {
        return new NovaChannelUpsertRequest(
                key,
                "Weekly recap",
                "每周任务完成后触发",
                "每周一 09:00",
                "7d",
                new BigDecimal("12.5"),
                true,
                "Marina K.",
                "新增周报推送通道");
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
}
