package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Seat capacity (M1 load config) moved out of the retired ticket service unchanged. */
class OpsSupportLoadConfigServiceTest {
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC"));
    private final SupportOwnershipService ownership = ffdd.opsconsole.content.SupportTestDependencies.ownership();
    private final OpsSupportLoadConfigService service = service();

    private OpsSupportLoadConfigService service() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .executeRetained(anyString(), anyString(), anyString(), any(), any());
        return new OpsSupportLoadConfigService(
                supportAgentService, configFacade, auditLogService, idempotencyService, clock, ownership);
    }

    @Test
    void loadConfigUsesPersistedPlatformConfigAndAgentState() {
        when(supportAgentService.availabilityStates()).thenReturn(Map.of("agent-1",Map.of("busy",false)));
        configFacade.values.put("content.support.load.autoBalance", "true");
        configFacade.values.put("content.support.load.defaultCap", "6");
        configFacade.values.put("content.support.load.burstCap", "10");
        configFacade.values.put("content.support.load.warnPct", "75");
        configFacade.values.put("content.support.load.quietHourBalance", "true");
        configFacade.values.put("content.support.load.overflowQueue", "夜间备勤队列");
        configFacade.values.put("content.support.load.lastRebalanceAt", "2026-06-18T00:00:00");
        configFacade.values.put("content.support.load.agent.agent-1.cap", "5");
        configFacade.values.put("content.support.load.agent.agent-1.busy", "true");

        var result = service.loadConfig();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("loadConfig")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("autoBalance", true)
                .containsEntry("defaultCap", 6)
                .containsEntry("burstCap", 10)
                .containsEntry("warnPct", 75)
                .containsEntry("quietHourBalance", true)
                .containsEntry("overflowQueue", "夜间备勤队列");
        assertThat(result.getData().get("agentState").toString()).contains("agent-1", "cap=5").doesNotContain("busy=true");
        assertThat(result.getData()).containsEntry("lastRebalanceAt", "2026-06-18T00:00:00");
        assertThat(result.getData().get("sources")).asList()
                .contains("nx_config_item:content_support_load", "nx_support_ticket", "nx_conversation");
    }

    @Test
    void loadConfigUsesSafeBusinessDefaultsWhenNoOverrideExists() {
        var result = service.loadConfig();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("loadConfig")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("autoBalance", false)
                .containsEntry("defaultCap", 8)
                .containsEntry("burstCap", 12)
                .containsEntry("warnPct", 80)
                .containsEntry("quietHourBalance", false)
                .containsEntry("overflowQueue", "转人工备勤队列");
    }

    @Test void loadConfigCannotReintroduceOtherGroupAgentFromHistoricalConfigKeys() {
        configFacade.values.put("content.support.load.agent.7.cap","5");
        configFacade.values.put("content.support.load.agent.8.cap","20");
        when(supportAgentService.availabilityStates()).thenReturn(Map.of("7",Map.of("busy",false)));
        var visible=(Map<?,?>)service.loadConfig().getData().get("agentState");
        assertThat(visible).hasSize(1);assertThat(visible.containsKey("7")).isTrue();
        when(supportAgentService.availabilityStates()).thenReturn(Map.of());
        assertThat((Map<?,?>)service.loadConfig().getData().get("agentState")).isEmpty();
    }

    @Test
    void updateLoadConfigRequiresIdempotencyKey() {
        var result = service.updateLoadConfig(null, loadConfigRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void updateLoadConfigPersistsConfigAndAudits() {
        var result = service.updateLoadConfig("idem-m1-load", loadConfigRequest());

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("content.support.load.defaultCap", "7")
                .containsEntry("content.support.load.burstCap", "11")
                .containsEntry("content.support.load.warnPct", "75")
                .containsEntry("content.support.load.version", "2")
                .containsEntry("content.support.load.agent.agent-1.cap", "5")
                .doesNotContainKey("content.support.load.agent.agent-1.busy");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M1_SUPPORT_LOAD_CONFIG_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-m1-load");
    }

    @Test
    void m1AvailabilityConflictPreventsConfigWritesAndRequiredAudit() {
        org.mockito.Mockito.doThrow(new ffdd.opsconsole.shared.exception.BizException(409,
                "SUPPORT_AGENT_PROFILE_VERSION_CONFLICT"))
                .when(supportAgentService).updateAvailabilityForLoad(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.updateLoadConfig("idem-m1-availability-conflict", loadConfigRequest()))
                .hasMessageContaining("SUPPORT_AGENT_PROFILE_VERSION_CONFLICT");

        assertThat(configFacade.values).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }
    @Test
    void m1ZeroCapsRoundTripWithoutBeingRewrittenByTheReadProjection() {
        when(supportAgentService.availabilityStates()).thenReturn(Map.of("agent-1",Map.of("busy",false)));
        var request = new SupportLoadConfigUpdateRequest(
                1L, true, 0, 0, 75, true, "备勤队列",
                Map.of("agent-1", new SupportAgentLoadStateRequest(0, null)),
                "superadmin", "zero-cap capacity hold");

        var result = service.updateLoadConfig("idem-m1-zero-cap", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("loadConfig"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("defaultCap", 0)
                .containsEntry("burstCap", 0);
        assertThat(result.getData().get("agentState").toString()).contains("agent-1", "cap=0");
    }
    @Test
    void updateLoadConfigRejectsAStaleVisibleVersion() {
        assertThat(service.updateLoadConfig("idem-m1-first", loadConfigRequest()).getCode()).isZero();

        var stale = service.updateLoadConfig("idem-m1-stale", loadConfigRequest());

        assertThat(stale.getCode()).isEqualTo(409);
        assertThat(stale.getMessage()).isEqualTo("SUPPORT_LOAD_VERSION_CONFLICT");
    }

    @Test
    void updateLoadConfigRequiresAnEightToTwoHundredCharacterReason() {
        var request = new SupportLoadConfigUpdateRequest(
                1L, true, 7, 11, 75, true, "备勤队列", Map.of(), "superadmin", "1234567");

        assertThat(service.updateLoadConfig("idem-m1-short-reason", request).getCode())
                .isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void rebalanceLoadPersistsTimestampAndAudits() {
        var result = service.rebalanceLoad(
                "idem-m1-rebalance",
                new SupportLoadRebalanceRequest(
                        1L,
                        List.of(Map.of("id", "agent-1", "cap", 4, "busy", false)),
                        "superadmin",
                        "shift load to backup team"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("content.support.load.agent.agent-1.cap", "4")
                .doesNotContainKey("content.support.load.agent.agent-1.busy")
                .containsEntry("content.support.load.version", "2")
                .containsKey("content.support.load.lastRebalanceAt");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M1_SUPPORT_LOAD_REBALANCED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static SupportLoadConfigUpdateRequest loadConfigRequest() {
        return new SupportLoadConfigUpdateRequest(
                1L,
                true,
                7,
                11,
                75,
                true,
                "备勤队列",
                Map.of("agent-1", new SupportAgentLoadStateRequest(5, true, 1L)),
                "superadmin",
                "rebalance support capacity");
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
            Map<String, String> matched = new LinkedHashMap<>();
            if ("content_support_load".equals(configGroup)) {
                values.forEach((key, value) -> {
                    if (key.startsWith("content.support.load.")) {
                        matched.put(key, value);
                    }
                });
            }
            return matched;
        }
    }
}
