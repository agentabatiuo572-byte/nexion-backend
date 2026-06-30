package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsPlatformConfigServiceTest {
    private final InMemoryPlatformConfigRepository repository = new InMemoryPlatformConfigRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsPlatformConfigService service = new OpsPlatformConfigService(
            repository,
            auditLogService,
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());

    @Test
    void overviewSeedsRemainingA3DataAndExcludesDeletedDomains() {
        repository.put(activeConfig(90L, "admin.system.ntp_source", "pool.ntp.org"));
        repository.put(activeConfig(91L, "admin.idempotency.window_hours", "24"));

        ApiResult<PlatformConfigOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().featureFlags()).extracting(flag -> flag.get("key"))
                .contains("ab.newWithdrawFlow", "core.sse_v2");
        assertThat(result.getData().killSwitches()).extracting(gate -> gate.get("key"))
                .contains("withdraw", "geo-block")
                .doesNotContain("premium", "nex-v2", "points");
        assertThat(result.getData().systemHealth()).extracting(row -> row.get("name"))
                .contains("事件管道(采集 -> 事件库)", "后台接口可用性(24h)")
                .doesNotContain("NTP 同步");
        assertThat(repository.items)
                .containsKeys(
                        "feature.ab.newWithdrawFlow",
                        "killswitch.withdraw",
                        "admin.health.event_pipeline");
        assertThat(repository.items.get("admin.system.ntp_source").status()).isZero();
        assertThat(repository.items.get("admin.idempotency.window_hours").status()).isZero();
    }

    @Test
    void disabledReadTimeSeedsDoNotExposeA3SeededCurrentState() {
        OpsPlatformConfigService realOnlyService = new OpsPlatformConfigService(
                repository,
                auditLogService,
                OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<PlatformConfigOverview> result = realOnlyService.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().featureFlags()).isEmpty();
        assertThat(result.getData().killSwitches()).isEmpty();
        assertThat(result.getData().systemHealth()).extracting(row -> row.get("name")).containsExactly("JVM 堆内存");
        assertThat(result.getData().stats())
                .containsEntry("flagCount", 0)
                .containsEntry("killGates", 0);
        assertThat(repository.items).doesNotContainKeys(
                "feature.ab.newWithdrawFlow",
                "killswitch.withdraw",
                "admin.health.event_pipeline");
    }

    @Test
    void updateRequiresIdempotencyKey() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("flag", "core.sse_v2", null, "on", "ops rollout", "superadmin");

        ApiResult<PlatformConfigResponse> result = service.update(" ", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void updateRequiresReason() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("flag", "core.sse_v2", null, "on", " ", "superadmin");

        ApiResult<PlatformConfigResponse> result = service.update("idem-1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
    }

    @Test
    void updateRejectsSunsetCapabilityKeys() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("flag", "premium.enabled", null, "on", "legacy rollback", "superadmin");

        ApiResult<PlatformConfigResponse> result = service.update("idem-1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SUNSET_CAPABILITY_READONLY");
        assertThat(repository.items).doesNotContainKey("feature.premium.enabled");
    }

    @Test
    void updateUpsertsFeatureFlagAndWritesA2Audit() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("flag", "core.sse_v2", null, "灰度 80%", "expand gray release", "superadmin");

        ApiResult<PlatformConfigResponse> result = service.update("idem-a3-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().configKey()).isEqualTo("feature.core.sse_v2");
        assertThat(result.getData().configValue()).isEqualTo("灰度 80%");
        assertThat(repository.items.get("feature.core.sse_v2").configGroup()).isEqualTo("admin_feature_flag");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("ADMIN_FEATURE_FLAG_CHANGED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A3_PLATFORM_CONFIG");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "expand gray release")
                .containsEntry("idempotencyKey", "idem-a3-1");
    }

    @Test
    void updateNormalizesKillSwitchAndMarksHighRisk() {
        PlatformConfigUpdateRequest request =
                new PlatformConfigUpdateRequest("gate", null, "withdraw", "disabled", "incident freeze", "superadmin");

        ApiResult<PlatformConfigResponse> result = service.update("idem-gate-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().configKey()).isEqualTo("killswitch.withdraw");
        assertThat(result.getData().configValue()).isEqualTo("disabled");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("HIGH");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static PlatformConfigItem activeConfig(Long id, String key, String value) {
        LocalDateTime now = LocalDateTime.now();
        return new PlatformConfigItem(id, key, value, "STRING", "admin_a3", "ADMIN", "legacy A3 config", 1, now, now);
    }

    private static final class InMemoryPlatformConfigRepository implements PlatformConfigRepository {
        private final Map<String, PlatformConfigItem> items = new LinkedHashMap<>();
        private long sequence = 1L;

        private void put(PlatformConfigItem item) {
            items.put(item.configKey(), item);
        }

        @Override
        public Optional<PlatformConfigItem> findActiveByKey(String configKey) {
            return Optional.ofNullable(items.get(configKey))
                    .filter(item -> item.status() != null && item.status() == 1);
        }

        @Override
        public List<PlatformConfigItem> findActiveByGroups(Collection<String> configGroups) {
            return items.values().stream()
                    .filter(item -> configGroups.contains(item.configGroup()))
                    .toList();
        }

        @Override
        public PlatformConfigItem save(PlatformConfigItem item) {
            PlatformConfigItem saved = item.id() == null
                    ? new PlatformConfigItem(
                            sequence++,
                            item.configKey(),
                            item.configValue(),
                            item.valueType(),
                            item.configGroup(),
                            item.visibility(),
                            item.remark(),
                            item.status(),
                            LocalDateTime.now(),
                            LocalDateTime.now())
                    : item;
            items.put(saved.configKey(), saved);
            return saved;
        }
    }
}
