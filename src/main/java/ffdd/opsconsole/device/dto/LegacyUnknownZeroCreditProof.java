package ffdd.opsconsole.device.dto;

import java.time.Instant;
import java.util.List;

/** The observation is signed as its original UTF-8 bytes, never reserialized for verification. */
public record LegacyUnknownZeroCreditProof(String observation, String signature) {
    public record Observation(int version, String purpose, String scope, String caseSetSha256,
            String backendInstanceId, String bootId, Instant capturedAt, Instant observedAt, Instant expiresAt,
            String evidenceSha256, String budgetSha256, BackendExit backend, List<WorkerExit> workers,
            List<ModelExit> models, List<JournalFingerprint> journals) { }
    public record ProcessIdentity(String containerId, long pid, long startTicks) { }
    public record BackendExit(ProcessIdentity previous, ProcessIdentity current, boolean previousExited) { }
    public record WorkerExit(String service, String activeState, long mainPid, boolean previousProcessesExited) { }
    public record ModelExit(String role, ProcessIdentity previous, ProcessIdentity current,
            boolean previousProcessesExited, String evidenceSha256) { }
    public record JournalFingerprint(long deviceId, String bindingSha256, String stateSha256,
            String callsSha256, String contentSha256) { }
}
