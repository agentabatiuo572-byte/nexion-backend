package ffdd.opsconsole.emergency.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.mapper.EmergencyControlMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MybatisEmergencyControlRepositoryTest {

    @Test
    void disableRetriesWhenConcurrentRestoreMaterializesAnInitiallyAbsentPrimaryRow() {
        EmergencyControlMapper mapper = mock(EmergencyControlMapper.class);
        when(mapper.disableExistingSettingIfEnabled(
                "killswitch.withdraw", "emergency.killswitch.withdraw.enabled", "system:j1-auto-trigger"))
                .thenReturn(0, 1);
        when(mapper.insertDisabledSettingIfEffectiveDefaultEnabled(
                "killswitch.withdraw", "emergency.killswitch.withdraw.enabled", "system:j1-auto-trigger"))
                .thenReturn(0);
        MybatisEmergencyControlRepository repository =
                new MybatisEmergencyControlRepository(mapper, new ObjectMapper());

        boolean disabled = repository.disableKillSwitchIfEnabled(
                "killswitch.withdraw", "emergency.killswitch.withdraw.enabled", "system:j1-auto-trigger");

        assertThat(disabled).isTrue();
        verify(mapper, times(2)).disableExistingSettingIfEnabled(
                "killswitch.withdraw", "emergency.killswitch.withdraw.enabled", "system:j1-auto-trigger");
    }

    @Test
    void orphanRepairResurrectsADeletedPendingTombstoneBeforeTryingInsert() {
        EmergencyControlMapper mapper = mock(EmergencyControlMapper.class);
        when(mapper.restoreDeletedAutoConfirmation(
                "pending", "gate", "emergency", "lastChange", "system:j1-repair"))
                .thenReturn(1);
        MybatisEmergencyControlRepository repository =
                new MybatisEmergencyControlRepository(mapper, new ObjectMapper());

        boolean claimed = repository.claimMissingAutoConfirmation(
                "pending", "gate", "emergency", "lastChange", "system:j1-repair");

        assertThat(claimed).isTrue();
        verify(mapper, never()).claimMissingAutoConfirmation(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void executionInflatesStepStringsAndStructuredDomainActionsWithoutTypeConfusion() {
        EmergencyControlMapper mapper = mock(EmergencyControlMapper.class);
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "executionId", "EXEC-1",
                "timestamp", "2026-07-15 12:00:01",
                "stepsJson", "[\"running\",\"pending\"]",
                "notificationJson", "{\"auditStatus\":\"PENDING\"}",
                "domainActionsJson", "[{\"domain\":\"J1\",\"status\":\"APPLIED\"}]"));
        when(mapper.execution("EXEC-1")).thenReturn(row);
        MybatisEmergencyControlRepository repository =
                new MybatisEmergencyControlRepository(mapper, new ObjectMapper());

        Map<String, Object> execution = repository.execution("EXEC-1").orElseThrow();

        assertThat(execution.get("steps")).isEqualTo(List.of("running", "pending"));
        assertThat(execution.get("domainActions")).isEqualTo(
                List.of(Map.of("domain", "J1", "status", "APPLIED")));
        assertThat(execution.get("rollbackActions")).isEqualTo(List.of());
    }

    @Test
    void executionFailsClosedWhenRequiredJsonIsMissingOrCorrupt() {
        EmergencyControlMapper mapper = mock(EmergencyControlMapper.class);
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "executionId", "EXEC-BAD",
                "timestamp", "2026-07-15 12:00:02",
                "stepsJson", "not-json",
                "notificationJson", "{}",
                "domainActionsJson", "[{\"domain\":\"J1\"}]"));
        when(mapper.execution("EXEC-BAD")).thenReturn(row);
        MybatisEmergencyControlRepository repository =
                new MybatisEmergencyControlRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.execution("EXEC-BAD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("J4_EXECUTION_JSON_CORRUPT:step_status_json");
    }

    @Test
    void executionFailsClosedWhenStepStatusIsOutsideTheContractEnum() {
        EmergencyControlMapper mapper = mock(EmergencyControlMapper.class);
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "executionId", "EXEC-STATUS",
                "timestamp", "2026-07-15 12:00:03",
                "stepsJson", "[\"garbage\"]",
                "notificationJson", "{}",
                "domainActionsJson", "[{\"domain\":\"J1\"}]"));
        when(mapper.execution("EXEC-STATUS")).thenReturn(row);
        MybatisEmergencyControlRepository repository =
                new MybatisEmergencyControlRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.execution("EXEC-STATUS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("J4_EXECUTION_JSON_CORRUPT:step_status_json");
    }

    @Test
    void executionIndependentAlwaysStartsAFreshReadOnlyTransaction() throws Exception {
        var method = MybatisEmergencyControlRepository.class
                .getMethod("executionIndependent", String.class);
        var transaction = method.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertThat(transaction.readOnly()).isTrue();
    }
}
