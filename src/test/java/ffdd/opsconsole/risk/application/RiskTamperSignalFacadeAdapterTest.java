package ffdd.opsconsole.risk.application;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import org.junit.jupiter.api.Test;

class RiskTamperSignalFacadeAdapterTest {
    @Test
    void writesMachineReadableEvidenceIntoTheSharedRiskSignalTable() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        var adapter = new RiskTamperSignalFacadeAdapter(repository, new ObjectMapper());
        when(repository.projectTamperSignal(eq("TAMPER-event-42"), eq(42L), eq(null), anyString(), eq(1), eq(true), eq("risk.tamper_detected")))
                .thenReturn(new RiskOpsRepository.TamperProjection(true, 1, true));

        adapter.recordTamperSignal(
                "event-42", 42L, null, "risk_disclosure_ack",
                "server rejected stale version", "/api/legal/risk-disclosure/acknowledgment", 1, true);

        verify(repository).projectTamperSignal(
                eq("TAMPER-event-42"), eq(42L), eq(null),
                argThat(evidence -> evidence.startsWith("{")
                        && evidence.contains("\"objectAction\":\"risk.tamper_detected\"")
                        && evidence.contains("\"tamperPath\":\"risk_disclosure_ack\"")),
                eq(1),
                eq(true),
                eq("risk.tamper_detected"));
    }
}
