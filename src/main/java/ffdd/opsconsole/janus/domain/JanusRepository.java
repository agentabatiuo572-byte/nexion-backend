package ffdd.opsconsole.janus.domain;

import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyUpsertRequest;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface JanusRepository {
    PageResult<JanusDeviceView> pageDevices(JanusDeviceQueryRequest request);

    Optional<JanusDeviceView> findDevice(String sid);

    void upsertDeviceReport(long userId, String sid, JanusDeviceReportRequest request);

    boolean insertEvaluation(String sid, String reportId, String sessionId, String strategyId,
                             Integer strategyVersion, String inputSnapshotJson, String ruleResultsJson,
                             String action, String recommendedStatus, String errorCode, int elapsedMs,
                             String engineVersion);

    boolean reserveDailyEvaluation(String strategyId, String action, int cap);

    void releaseDailyEvaluation(String strategyId, String action);

    boolean expireDeviceOverride(long userId, String sid, long now);

    int expireDeviceOverrides(long now);

    Optional<Map<String, Object>> findPendingDeviceCommand(long userId, String sid);

    boolean acknowledgeDeviceCommand(long userId, String sid, long revision, boolean success, String appliedStatus);

    boolean isDeviceCommandAckReplay(long userId, String sid, long revision, boolean success, String appliedStatus);

    void updateDeviceCommandRecord(String sid, String state);

    boolean updateDeviceStatus(String sid, long expectedVersion, String targetStatus,
                               String remoteUrlKey, String operator, String reason, String manualOverrideJson,
                               String commandState);

    boolean publishStrategyCommand(String sid, long expectedVersion, String targetStatus,
                                   String remoteUrlKey, String payloadJson);

    List<JanusStrategyView> strategies();

    Optional<JanusStrategyView> findStrategy(String strategyId);

    JanusStrategyView createStrategy(String strategyId, JanusStrategyUpsertRequest request);

    Optional<JanusStrategyView> updateStrategy(String strategyId, long expectedVersion, JanusStrategyUpsertRequest request);

    boolean updateStrategyLifecycle(String strategyId, long expectedVersion, String status, int version, Long publishedAt);

    boolean replaceStrategyFromSnapshot(String strategyId, long expectedVersion, int version, String status, String snapshotJson);

    boolean deleteStrategy(String strategyId, long expectedVersion);

    void addStrategyVersion(String strategyId, int version, String note, String actorId, String snapshotJson, String configHash);

    Optional<JanusStrategyVersionView> findStrategyVersion(String strategyId, int version);

    void saveDryRun(String dryRunId, String strategyId, long expectedVersion, String configHash, String resultJson,
                    String actorId, long expiresAt);

    Optional<Map<String, Object>> findDryRun(String dryRunId);

    Optional<Map<String, Object>> findCommand(String idempotencyKey);

    boolean reserveCommand(String idempotencyKey, String commandType, String targetId, String requestHash,
                           String actorId);

    void completeCommand(String idempotencyKey, String state, String payloadJson);

    void releaseCommandReservation(String idempotencyKey);

}
