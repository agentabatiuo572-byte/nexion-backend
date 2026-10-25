package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest.Tier;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceRankConfigServiceTest {
    final Map<String, String> store = new HashMap<>();
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final MockEnvironment env = new MockEnvironment();
    DeviceRankConfigService service;
    List<Tier> tiers = List.of(new Tier("Reference", new BigDecimal("100"), BigDecimal.ONE, BigDecimal.TEN));

    @BeforeEach void setup() {
        env.setActiveProfiles("dev");
        when(config.activeValue(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        when(config.activeValueForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        when(config.insertAdminValueIfMissing(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; }).when(config).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        service = new DeviceRankConfigService(config, new ObjectMapper(), audit, env, Clock.systemUTC());
    }
    DeviceRankConfigRequest request(long version, boolean enabled, List<Tier> values) {
        return new DeviceRankConfigRequest(version, enabled, values, "Configure simulated ranking", "operator");
    }
    @Test void defaultIsDisabledAndNoHardwareGuessesAreStored() {
        assertThat(service.overview().getData()).containsEntry("version", 0L).containsEntry("enabled", false).containsEntry("tiers", List.of());
        assertThat(store).isEmpty();
    }
    @Test void casNoOpAndTogglesPreserveSeedButDistributionChangeGeneratesNewModel() {
        assertThat(service.update(request(0, true, tiers), "first").getCode()).isZero();
        var first = service.model(service.scope());
        assertThat(service.update(request(0, false, tiers), "stale").getCode()).isEqualTo(409);
        assertThat(service.update(request(1, true, tiers), "same").getData().get("version")).isEqualTo(1L);
        service.update(request(1, false, tiers), "off");
        service.update(request(2, true, tiers), "on");
        assertThat(service.model(service.scope()).seed()).isEqualTo(first.seed());
        assertThat(service.model(service.scope()).generatedAt()).isEqualTo(first.generatedAt());
        var renamed = List.of(new Tier("Renamed", new BigDecimal("100"), BigDecimal.ONE, BigDecimal.TEN));
        service.update(request(3, true, renamed), "rename");
        assertThat(service.model(service.scope()).seed()).isEqualTo(first.seed());
        var changed = List.of(new Tier("Changed", new BigDecimal("100"), BigDecimal.TEN, new BigDecimal("20")));
        service.update(request(4, true, changed), "change");
        assertThat(service.model(service.scope()).seed()).isNotEqualTo(first.seed());
        verify(audit, times(5)).recordRequired(any());
    }
    @Test void invalidRequestsCannotChangeDurableConfig() {
        for (List<Tier> invalid : List.of(List.<Tier>of(), List.of(new Tier("", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ONE)),
                List.of(new Tier("a", new BigDecimal("99"), BigDecimal.ZERO, BigDecimal.ONE)),
                List.of(new Tier("a", new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ONE)),
                List.of(new Tier("a", new BigDecimal("100"), BigDecimal.TEN, BigDecimal.ONE)),
                List.of(new Tier("a", new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1000000001"))),
                List.of(new Tier("a", new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("0.0000001"))))) {
            assertThat(service.update(request(0, true, invalid), "invalid").getCode()).isEqualTo(422);
        }
        assertThat(service.update(new DeviceRankConfigRequest(0L, true, tiers, "short", null), "bad").getCode()).isEqualTo(422);
        assertThat(store).isEmpty();
        verifyNoInteractions(audit);
    }
    @Test void sandboxScopeNeverReadsProductionConfigAndBadStoredDataDoesNotDefault() {
        service.update(request(0, true, tiers), "prod");
        env.setActiveProfiles("test");
        env.setProperty("NEXION_ACCEPTANCE_RUN_ID", "rank-run-20261025");
        assertThat(service.overview().getData()).containsEntry("enabled", false);
        store.put(service.scope().key(), "not-json");
        assertThatThrownBy(service::overview).hasMessageContaining("DEVICE_RANK_CONFIG_UNAVAILABLE");
    }
    @Test void longestSandboxRunFitsIdempotencyScopeAndPreservesCaseIsolation() {
        var upper = new DeviceRankConfigService.Scope("SANDBOX", "A".repeat(96));
        var lower = new DeviceRankConfigService.Scope("SANDBOX", "a".repeat(96));
        assertThat("GROWTH:H9:DEVICE_RANK_UPDATE:" + upper.key()).hasSizeLessThanOrEqualTo(96);
        assertThat(upper.key().toUpperCase()).isNotEqualTo(lower.key().toUpperCase());
    }
}
