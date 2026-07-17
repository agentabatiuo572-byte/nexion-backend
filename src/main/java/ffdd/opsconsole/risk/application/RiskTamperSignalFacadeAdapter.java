package ffdd.opsconsole.risk.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes the shared K4/B5 risk-signal source from the same canonical event consumed by J3. */
@Component
@RequiredArgsConstructor
public class RiskTamperSignalFacadeAdapter implements RiskTamperSignalFacade {
    private final RiskOpsRepository riskRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TamperProjectionResult recordTamperSignal(
            String eventId,
            Long userId,
            String userNo,
            String tamperPath,
            String attackEffect,
            String blockedAtEndpoint,
            int eventCount,
            boolean feedK4) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("objectAction", "risk.tamper_detected");
        evidence.put("eventId", eventId);
        evidence.put("userNo", userNo);
        evidence.put("tamperPath", tamperPath);
        evidence.put("attackEffect", attackEffect);
        evidence.put("blockedAtEndpoint", blockedAtEndpoint);
        RiskOpsRepository.TamperProjection projection = riskRepository.projectTamperSignal(
                "TAMPER-" + eventId.substring(0, Math.min(eventId.length(), 40)),
                userId,
                userNo,
                evidenceJson(evidence),
                eventCount,
                feedK4,
                "risk.tamper_detected");
        return new TamperProjectionResult(
                projection.k4Accepted(), projection.k4Delta(), projection.b5Accepted());
    }

    @Override
    public TamperRadarSnapshot tamperRadarSnapshot(LocalDateTime since) {
        RiskOpsRepository.TamperRadarSnapshot snapshot = riskRepository.tamperRadarSnapshot(since);
        return new TamperRadarSnapshot(snapshot.signalCount(), snapshot.accountCount(), snapshot.latestAt());
    }

    private String evidenceJson(Map<String, Object> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("TAMPER_SIGNAL_EVIDENCE_SERIALIZE_FAILED", ex);
        }
    }
}
