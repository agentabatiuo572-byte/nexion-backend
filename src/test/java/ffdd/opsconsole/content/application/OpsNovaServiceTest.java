package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.dto.NovaChannelStatusRequest;
import ffdd.opsconsole.content.dto.NovaChannelUpsertRequest;
import ffdd.opsconsole.content.dto.NovaDeleteRequest;
import ffdd.opsconsole.content.dto.NovaDistributionUpdateRequest;
import ffdd.opsconsole.content.dto.NovaPoolUpdateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateCreateRequest;
import ffdd.opsconsole.content.dto.NovaTemplateStatusRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsNovaServiceTest {
    private final FakeNovaRepository novaRepository = new FakeNovaRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsNovaService service = new OpsNovaService(
            novaRepository,
            auditLogService);

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
                        "nx_nova_social_pool",
                        "nx_notification");
    }

    @Test
    void overviewDoesNotExposeStaticNovaSeedRows() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().channels()).isEmpty();
        assertThat(result.getData().templates()).isEmpty();
        assertThat(result.getData().socialDistribution()).isEmpty();
        assertThat(result.getData().socialPools()).isEmpty();
        assertThat(result.getData().eventDriven()).isEmpty();
        assertThat(result.getData().stats().totalChannels()).isZero();
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
                .containsExactly("Weekly recap", "每周任务完成后触发", true);

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
        var result = service.createTemplate("idem-i2-template", new NovaTemplateCreateRequest(
                "weeklyRecap",
                "每周回顾",
                "→ /me/weekly",
                "v1",
                "Marina K.",
                "新增每周回顾模板"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("DRAFT");
        assertThat(novaRepository.template("weeklyRecap")).get()
                .extracting(NovaTemplateView::status)
                .isEqualTo("DRAFT");
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
    void updatePoolPersistsCount() {
        var result = service.updatePool("CITIES", "idem-i2-pool", new NovaPoolUpdateRequest(
                40,
                "Marina K.",
                "新增城市池条目"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().count()).isEqualTo(40);
        assertThat(novaRepository.socialPool("CITIES")).get()
                .extracting(NovaSocialPoolView::count)
                .isEqualTo(40);
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

    private static final class FakeNovaRepository implements NovaRepository {
        private final Map<String, NovaChannelView> channels = new LinkedHashMap<>();
        private final Map<String, NovaTemplateView> templates = new LinkedHashMap<>();
        private final List<NovaSocialDistributionItem> distribution = new ArrayList<>();
        private final Map<String, NovaSocialPoolView> pools = new LinkedHashMap<>();
        private final Map<String, Object> stats = new LinkedHashMap<>();

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
            channels.put(key, new NovaChannelView(key, name, trigger, tick, cooldown, "", ctr, enabled));
        }

        @Override
        public void updateChannel(String key, String name, String trigger, String tick, String cooldown,
                                  BigDecimal ctr, boolean enabled, String operator, String reason) {
            channels.put(key, new NovaChannelView(key, name, trigger, tick, cooldown, "", ctr, enabled));
        }

        @Override
        public void updateChannelStatus(String key, boolean enabled, String operator, String reason) {
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
        public void createTemplate(String channel, String name, String cta, String version, String operator, String reason) {
            templates.put(channel, new NovaTemplateView(channel, name, cta, version, "DRAFT"));
        }

        @Override
        public void updateTemplateStatus(String channel, String status, String operator, String reason) {
            NovaTemplateView current = templates.get(channel);
            templates.put(channel, new NovaTemplateView(current.channel(), current.name(), current.cta(), current.version(), status));
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
    }
}
