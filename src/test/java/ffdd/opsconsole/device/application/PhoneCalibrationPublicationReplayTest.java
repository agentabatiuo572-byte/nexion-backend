package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.finance.facade.E4OrderRefundSettlementFacade;
import ffdd.opsconsole.onboarding.application.PhoneCalibrationConfigService;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.platform.application.PlatformConfigFacadeAdapter;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.OutboxProperties;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PhoneCalibrationPublicationReplayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T05:00:00Z"), ZoneOffset.UTC);
    private final Map<String, PlatformConfigItem> rows = new HashMap<>();
    private final PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
    private final PlatformConfigFacadeAdapter config = new PlatformConfigFacadeAdapter(repository);
    private final EventOutboxMapper eventMapper = mock(EventOutboxMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    private OpsDeviceService service() throws Exception {
        when(repository.findActiveByKey(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(repository.findActiveByKeyForUpdate(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(repository.insertIfAbsent(any())).thenAnswer(call -> {
            PlatformConfigItem value = call.getArgument(0);
            return rows.putIfAbsent(value.configKey(), value) == null;
        });
        when(repository.save(any())).thenAnswer(call -> {
            PlatformConfigItem value = call.getArgument(0);
            rows.put(value.configKey(), value);
            return value;
        });
        // Use the checked-in registry contract, not a permissive mocked publisher.
        String migration = Files.readString(Path.of("scripts/migrations/20260721_e6_compute_config_closure.sql"));
        var matcher = Pattern.compile("SELECT 'compute\\.config_changed','([^']+)','([^']+)'").matcher(migration);
        var properties = new ArrayList<EventOutboxMapper.SchemaPropertyGateRow>();
        while (matcher.find()) properties.add(new EventOutboxMapper.SchemaPropertyGateRow(
                matcher.group(1), matcher.group(2), !Set.of("param_key", "keys").contains(matcher.group(1))));
        assertThat(properties).hasSize(7);
        when(eventMapper.findActiveSchema("compute.config_changed")).thenReturn(new EventOutboxMapper.SchemaGateRow("compute-config", 60, true));
        when(eventMapper.listActiveProperties("compute.config_changed")).thenReturn(properties);
        when(eventMapper.findLifecycleState("compute.config_changed")).thenReturn("full");
        var events = new EventOutboxService(eventMapper, json, new OutboxProperties(), mock(A4RuntimePolicyService.class));
        var idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        var coverage = mock(TreasuryCoverageFacade.class);
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(BigDecimal.valueOf(200), BigDecimal.valueOf(100), true));
        return new OpsDeviceService(mock(SupportPaymentAttributionFacade.class), mock(DeviceOpsRepository.class),
                mock(DeviceCatalogRepository.class), config, mock(TreasuryLedgerPostingFacade.class),
                mock(E4OrderRefundSettlementFacade.class), coverage, audit, idempotency, events,
                mock(E2TaskPriceHistoryService.class), clock, OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                mock(AuditObjectLockMapper.class), mock(ObjectStorageService.class));
    }

    private AuditReplayCommand command(long effectiveAt) {
        return new AuditReplayCommand("E", "e6_phone_calibration", Map.of(
                "expectedRevision", 0, "effectiveAt", effectiveAt, "thresholds", List.of(10, 20, 30, 40),
                "rules", List.of(Map.of("id", "TEMP_TEST_PH05_V1", "platform", "android", "model", "SM-A5760",
                        "soc", "s5e8865", "gpu", "ANGLE ((Samsung Xclipse 550) on Vulkan 1.4.304)",
                        "minMemoryGb", 7, "maxMemoryGb", 8, "computeValue", 15, "evidence", "Temporary fixture rule"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void approvedFirstPublicationUsesRegisteredEventContractAndReadback(boolean scheduled) throws Exception {
        var service = service();
        assertThat(new PhoneCalibrationConfigService(config, clock).read().revision()).isZero();
        A2ReplayContext.enterReplay("WO-PHONE-FIXTURE");
        try {
            var result = service.replay(command(scheduled ? clock.millis() + 60000 : 0),
                    new AuditReplayContext("checker-fixture", "Verify temporary phone rule", "phone-first-publication"));
            assertThat(result.getCode()).isZero();
        } finally {
            A2ReplayContext.exitReplay();
        }
        var saved = new PhoneCalibrationConfigService(config, clock).read();
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(scheduled ? saved.scheduled().rules() : saved.current().rules()).hasSize(1);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(eventMapper).insertEvent(anyString(), eq("E6_COMPUTE_CONFIG"), eq("phone-calibration"),
                eq("compute.config_changed"), eq("compute.config_changed"), eq("compute-config"),
                eq("SYSTEM"), eq(0), anyString(), eq(true), eq(60), eq(true), eq(true), payload.capture());
        var event = json.readTree(payload.getValue());
        assertThat(event.path("before").path("revision").asLong()).isZero();
        assertThat(event.path("after").path("revision").asLong()).isEqualTo(1);
        assertThat(event.path("after").path(scheduled ? "scheduled" : "current").path("version").asLong()).isEqualTo(1);
        assertThat(event.path("reason").asText()).isEqualTo("Verify temporary phone rule");
        assertThat(event.path("operator").asText()).isEqualTo("checker-fixture");
        assertThat(event.path("param_key").asText()).isEqualTo(PhoneCalibrationConfigService.KEY);
        var auditRow = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequired(auditRow.capture());
        assertThat(auditRow.getValue().getAction()).isEqualTo("phone.calibration_policy_published");
    }

    @Test
    void publicationWithoutA2ReplayRemainsRejectedBeforeAnyConfigurationWrite() throws Exception {
        var result = service().replay(command(0), new AuditReplayContext("checker-fixture", "Verify temporary phone rule", "phone-first-publication"));
        assertThat(result.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        assertThat(rows).isEmpty();
        verify(repository, never()).save(any());
        verifyNoInteractions(eventMapper, audit);
    }
}
