package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.dto.PlatformConfigOverview;
import ffdd.opsconsole.platform.dto.PlatformConfigResponse;
import ffdd.opsconsole.platform.dto.PlatformConfigUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsPlatformConfigServiceTest {
    private final InMemoryPlatformConfigRepository repository = new InMemoryPlatformConfigRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final AdminOperatorRoleResolver roleResolver = mock(AdminOperatorRoleResolver.class);
    private final PlatformEmergencyStateProvider emergencyStateProvider = mock(PlatformEmergencyStateProvider.class);
    private final PlatformSystemHealthProvider healthProvider = mock(PlatformSystemHealthProvider.class);
    private final OpsPlatformConfigService service = new OpsPlatformConfigService(
            repository,
            auditLogService,
            idempotencyService,
            roleResolver,
            emergencyStateProvider,
            healthProvider);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(roleResolver.resolveCode()).thenReturn("SUPER_ADMIN");
        when(emergencyStateProvider.currentKillSwitches()).thenReturn(List.of(
                Map.of("key", "exchange", "name", "兑换闸", "status", "disabled", "up", false,
                        "lastChange", "2026-07-17T10:00:00", "chain", "J1 / nx_emergency_control_setting"),
                Map.of("key", "geo-block", "name", "地区屏蔽", "status", "2 个国家已屏蔽", "up", false,
                        "lastChange", "2026-07-17T10:01:00", "chain", "J2 / nx_emergency_geo_country_policy")));
        when(healthProvider.currentHealth()).thenReturn(List.of(
                Map.of("name", "事件待投递", "tone", "ok", "metric", "0 条", "source", "nx_event_outbox",
                        "observedAt", "2026-07-17T10:02:00", "stale", false)));
        doAnswer(invocation -> ((Supplier<ApiResult<PlatformConfigResponse>>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void overviewUsesOnlyPersistedRegisteredFlagsAndAuthoritativeJ1J2State() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "off", "admin_feature_flag"));
        repository.put(activeConfig(2L, "feature.unknownUnusedFlag", "on", "admin_feature_flag"));
        repository.put(activeConfig(3L, "killswitch.exchange", "enabled", "admin_killswitch"));

        ApiResult<PlatformConfigOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().featureFlags()).hasSize(1);
        assertThat(result.getData().featureFlags().get(0))
                .containsEntry("key", "ops.maintenanceBanner")
                .containsEntry("status", "off")
                .containsEntry("writable", true)
                .containsEntry("consumer", "ops-console-shell");
        assertThat(result.getData().killSwitches()).extracting(row -> row.get("key"))
                .containsExactly("exchange", "geo-block");
        assertThat(result.getData().killSwitches().get(0)).containsEntry("status", "disabled");
        assertThat(result.getData().systemHealth().get(0))
                .containsEntry("source", "nx_event_outbox")
                .containsEntry("stale", false);
        assertThat(result.getData().stats())
                .containsEntry("flagCount", 1)
                .containsEntry("killGates", 2)
                .containsEntry("killGatesUp", 0L);
    }

    @Test
    void overviewDoesNotInventMissingFlagState() {
        ApiResult<PlatformConfigOverview> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().featureFlags()).isEmpty();
        assertThat(repository.items).isEmpty();
    }

    @Test
    void runtimeFlagsUseTheSamePersistedSource() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "on", "admin_feature_flag"));

        ApiResult<Map<String, Object>> result = service.runtimeFlags();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("maintenanceBanner", true)
                .containsEntry("source", "nx_config_item:feature.ops.maintenanceBanner");
    }

    @Test
    void updateRejectsUnknownFlagInvalidValueAndInvalidReasonBounds() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "off", "admin_feature_flag"));

        assertFailure(request("other.flag", "on", "off", "valid reason"), "A3_FLAG_UNKNOWN");
        assertFailure(request("ops.maintenanceBanner", "abc", "off", "valid reason"), "A3_FLAG_VALUE_INVALID");
        assertFailure(request("ops.maintenanceBanner", "on", "off", "short"), "A3_REASON_LENGTH_INVALID");
        assertFailure(request("ops.maintenanceBanner", "on", "off", "x".repeat(201)), "A3_REASON_LENGTH_INVALID");
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void updateRejectsSameValueStaleValueAndUnauthorizedRole() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "off", "admin_feature_flag"));

        assertFailure(request("ops.maintenanceBanner", "off", "off", "same value reason"), "A3_FLAG_SAME_VALUE");
        assertFailure(request("ops.maintenanceBanner", "on", "on", "stale value reason"), "A3_FLAG_STALE");
        when(roleResolver.resolveCode()).thenReturn("AUDITOR");
        assertFailure(request("ops.maintenanceBanner", "on", "off", "auditor must fail"), "A3_FLAG_ROLE_FORBIDDEN");
        assertThat(repository.items.get("feature.ops.maintenanceBanner").configValue()).isEqualTo("off");
    }

    @Test
    void updateWritesBeforeAfterAuditAndUsesAuthenticatedActor() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "off", "admin_feature_flag"));
        PlatformConfigUpdateRequest request = new PlatformConfigUpdateRequest(
                "flag", "ops.maintenanceBanner", null, "on", "off", "planned maintenance", "spoofed-user");

        ApiResult<PlatformConfigResponse> result = service.update("idem-a3-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().configValue()).isEqualTo("on");
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isNotEqualTo("spoofed-user");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("before", "off")
                .containsEntry("after", "on")
                .containsEntry("reason", "planned maintenance")
                .containsEntry("consumer", "ops-console-shell")
                .containsEntry("role", "SUPER_ADMIN")
                .containsEntry("idempotencyKey", "idem-a3-1");
    }

    @Test
    void updateDelegatesReplayProtectionToTwentyFourHourIdempotencyStore() {
        repository.put(activeConfig(1L, "feature.ops.maintenanceBanner", "off", "admin_feature_flag"));
        PlatformConfigUpdateRequest request = request(
                "ops.maintenanceBanner", "on", "off", "planned maintenance");

        service.update("idem-a3-2", request);

        verify(idempotencyService).execute(
                org.mockito.ArgumentMatchers.eq("A3_FEATURE_FLAG:ops.maintenanceBanner"),
                org.mockito.ArgumentMatchers.eq("idem-a3-2"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(ApiResult.class),
                any());
    }

    private void assertFailure(PlatformConfigUpdateRequest request, String message) {
        ApiResult<PlatformConfigResponse> result = service.update("idem-test-" + message, request);
        assertThat(result.getCode()).isIn(
                OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo(message);
    }

    private static PlatformConfigUpdateRequest request(String key, String value, String expectedValue, String reason) {
        return new PlatformConfigUpdateRequest("flag", key, null, value, expectedValue, reason, "spoofed-user");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static PlatformConfigItem activeConfig(Long id, String key, String value, String group) {
        LocalDateTime now = LocalDateTime.now();
        return new PlatformConfigItem(id, key, value, "STRING", group, "ADMIN", "test config", 1, now, now);
    }

    private static final class InMemoryPlatformConfigRepository implements PlatformConfigRepository {
        private final Map<String, PlatformConfigItem> items = new LinkedHashMap<>();

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
            items.put(item.configKey(), item);
            return item;
        }
    }
}
