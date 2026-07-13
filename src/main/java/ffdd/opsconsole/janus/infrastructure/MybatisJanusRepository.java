package ffdd.opsconsole.janus.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.janus.domain.JanusDeviceView;
import ffdd.opsconsole.janus.domain.JanusRepository;
import ffdd.opsconsole.janus.domain.JanusStrategyVersionView;
import ffdd.opsconsole.janus.domain.JanusStrategyView;
import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusDeviceReportRequest;
import ffdd.opsconsole.janus.dto.JanusStrategyUpsertRequest;
import ffdd.opsconsole.janus.mapper.JanusMapper;
import ffdd.opsconsole.shared.api.PageResult;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DuplicateKeyException;

@Repository
@RequiredArgsConstructor
public class MybatisJanusRepository implements JanusRepository {
    private final JanusMapper mapper;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void ensureSchema() {
        mapper.createDeviceTable();
        if (mapper.countDeviceUserIdColumn() == 0) mapper.addDeviceUserIdColumn();
        if (mapper.countDeviceOwnerIndex() == 0) mapper.addDeviceOwnerIndex();
        mapper.createStrategyTable();
        mapper.createStrategyVersionTable();
        mapper.createEvaluationTable();
        mapper.createDailyQuotaTable();
        mapper.syncDailyQuotaFromEvaluations();
        mapper.createDryRunTable();
        mapper.createCommandTable();
        if (mapper.countCommandExpiresAtColumn() == 0) mapper.addCommandExpiresAtColumn();
        mapper.expireLegacyCommands();
    }

    @Override
    public PageResult<JanusDeviceView> pageDevices(JanusDeviceQueryRequest request) {
        JanusDeviceQueryRequest query = request == null
                ? new JanusDeviceQueryRequest(null, null, null, null, null, 1, 25)
                : request;
        int pageNum = clamp(query.pageNum(), 1, Integer.MAX_VALUE, 1);
        int pageSize = clamp(query.pageSize(), 1, 200, 25);
        String status = codeOrNull(query.status());
        String riskBand = lowerOrNull(query.riskBand());
        long total = mapper.countDevices(text(query.q()), status, riskBand, text(query.channel()), text(query.strategyId()));
        int maxPage = Math.max(1, (int) Math.min(Integer.MAX_VALUE, (total + pageSize - 1) / pageSize));
        pageNum = Math.min(pageNum, maxPage);
        List<JanusDeviceView> records = total == 0 ? List.of() : mapper.pageDevices(
                text(query.q()), status, riskBand, text(query.channel()), text(query.strategyId()),
                (long) (pageNum - 1) * pageSize, pageSize).stream().map(this::toDevice).toList();
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<JanusDeviceView> findDevice(String sid) {
        return Optional.ofNullable(mapper.findDevice(sid)).map(this::toDevice);
    }

    @Override
    public void upsertDeviceReport(long userId, String sid, JanusDeviceReportRequest request) {
        mapper.upsertDeviceReport(userId, sid, request, json(request.maturity()), json(request.environment()),
                nullableJson(request.latestDecision()), nullableJson(request.latestSession()), arrayJson(request.tags()));
    }

    @Override
    public boolean insertEvaluation(String sid, String reportId, String sessionId, String strategyId,
                                    Integer strategyVersion, String inputSnapshotJson, String ruleResultsJson,
                                    String action, String recommendedStatus, String errorCode, int elapsedMs,
                                    String engineVersion) {
        return mapper.insertEvaluation(sid, reportId, sessionId, strategyId, strategyVersion, inputSnapshotJson,
                ruleResultsJson, action, recommendedStatus, errorCode, elapsedMs, engineVersion) == 1;
    }

    @Override
    public boolean reserveDailyEvaluation(String strategyId, String action, int cap) {
        mapper.reserveDailyEvaluation(strategyId, action, cap);
        return mapper.lastInsertId() > 0;
    }

    @Override
    public void releaseDailyEvaluation(String strategyId, String action) {
        mapper.releaseDailyEvaluation(strategyId, action);
    }

    @Override
    public boolean expireDeviceOverride(long userId, String sid, long now) {
        return mapper.expireDeviceOverride(userId, sid, now) == 1;
    }

    @Override
    public int expireDeviceOverrides(long now) {
        return mapper.expireDeviceOverrides(now);
    }

    @Override
    public Optional<Map<String, Object>> findPendingDeviceCommand(long userId, String sid) {
        return Optional.ofNullable(mapper.findPendingDeviceCommand(userId, sid));
    }

    @Override
    public boolean acknowledgeDeviceCommand(long userId, String sid, long revision, boolean success,
                                            String appliedStatus) {
        return mapper.acknowledgeDeviceCommand(userId, sid, revision, success, appliedStatus) == 1;
    }

    @Override
    public boolean isDeviceCommandAckReplay(long userId, String sid, long revision, boolean success,
                                            String appliedStatus) {
        return mapper.countDeviceCommandAckReplay(userId, sid, revision, success, appliedStatus) > 0;
    }

    @Override
    public void updateDeviceCommandRecord(String sid, String state) {
        mapper.updateDeviceCommandRecord(sid, state);
    }

    @Override
    public boolean updateDeviceStatus(String sid, long expectedVersion, String targetStatus,
                                      String remoteUrlKey, String operator, String reason,
                                      String manualOverrideJson, String commandState) {
        return mapper.updateDeviceStatus(sid, expectedVersion, targetStatus, remoteUrlKey, operator,
                reason, manualOverrideJson, commandState) == 1;
    }

    @Override
    public boolean publishStrategyCommand(String sid, long expectedVersion, String targetStatus,
                                          String remoteUrlKey, String payloadJson) {
        return mapper.publishStrategyCommand(sid, expectedVersion, targetStatus, remoteUrlKey, payloadJson) == 1;
    }

    @Override
    public List<JanusStrategyView> strategies() {
        return mapper.strategies().stream().map(this::toStrategy).toList();
    }

    @Override
    public Optional<JanusStrategyView> findStrategy(String strategyId) {
        return Optional.ofNullable(mapper.findStrategy(strategyId)).map(this::toStrategy);
    }

    @Override
    public JanusStrategyView createStrategy(String strategyId, JanusStrategyUpsertRequest request) {
        mapper.insertStrategy(strategyId, request.name().trim(), text(request.description()), value(request.priority(), 0),
                request.owner().trim(), json(request.scope()), json(request.ruleTree()), json(request.action()),
                json(request.safeguards()), json(request.rollout()), json(request.healthConfig()), text(request.templateKey()));
        return findStrategy(strategyId).orElseThrow();
    }

    @Override
    public Optional<JanusStrategyView> updateStrategy(String strategyId, long expectedVersion,
                                                      JanusStrategyUpsertRequest request) {
        int updated = mapper.updateStrategy(strategyId, expectedVersion, request.name().trim(), text(request.description()),
                value(request.priority(), 0), request.owner().trim(), json(request.scope()), json(request.ruleTree()),
                json(request.action()), json(request.safeguards()), json(request.rollout()), json(request.healthConfig()),
                text(request.templateKey()));
        return updated == 0 ? Optional.empty() : findStrategy(strategyId);
    }

    @Override
    public boolean updateStrategyLifecycle(String strategyId, long expectedVersion, String status, int version,
                                           Long publishedAt) {
        return mapper.updateStrategyLifecycle(strategyId, expectedVersion, status, version, publishedAt) == 1;
    }

    @Override
    public boolean replaceStrategyFromSnapshot(String strategyId, long expectedVersion, int version, String status,
                                               String snapshotJson) {
        return mapper.replaceStrategyFromSnapshot(strategyId, expectedVersion, version, status, snapshotJson) == 1;
    }

    @Override
    public boolean deleteStrategy(String strategyId, long expectedVersion) {
        return mapper.deleteStrategy(strategyId, expectedVersion) == 1;
    }

    @Override
    public void addStrategyVersion(String strategyId, int version, String note, String actorId, String snapshotJson,
                                   String configHash) {
        mapper.insertStrategyVersion(strategyId, version, note, actorId, snapshotJson, configHash);
    }

    @Override
    public Optional<JanusStrategyVersionView> findStrategyVersion(String strategyId, int version) {
        return Optional.ofNullable(mapper.findStrategyVersion(strategyId, version)).map(this::toVersion);
    }

    @Override
    public void saveDryRun(String dryRunId, String strategyId, long expectedVersion, String configHash,
                           String resultJson, String actorId, long expiresAt) {
        mapper.insertDryRun(dryRunId, strategyId, expectedVersion, configHash, resultJson, actorId, expiresAt);
    }

    @Override
    public Optional<Map<String, Object>> findDryRun(String dryRunId) {
        return Optional.ofNullable(mapper.findDryRun(dryRunId));
    }

    @Override
    public Optional<Map<String, Object>> findCommand(String idempotencyKey) {
        return Optional.ofNullable(mapper.findCommand(idempotencyKey));
    }

    @Override
    public boolean reserveCommand(String idempotencyKey, String commandType, String targetId, String requestHash,
                                  String actorId) {
        mapper.deleteExpiredCommand(idempotencyKey);
        try {
            return mapper.insertCommandReservation(idempotencyKey, commandType, targetId, requestHash, actorId) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public void completeCommand(String idempotencyKey, String state, String payloadJson) {
        if (mapper.completeCommand(idempotencyKey, state, payloadJson) != 1) {
            throw new IllegalStateException("JANUS_IDEMPOTENCY_COMPLETION_FAILED");
        }
    }

    @Override
    public void releaseCommandReservation(String idempotencyKey) {
        mapper.releaseCommandReservation(idempotencyKey);
    }

    private JanusDeviceView toDevice(JanusDeviceRecord row) {
        return new JanusDeviceView(row.getSid(), row.getDeviceId(), row.getFirstSeenAt(), row.getLastSeenAt(),
                row.getInstallAt(), row.getInstallDays(), row.getInviteCode(), row.getChannel(), row.getCohortId(),
                row.getStatus(), row.getDesiredStatus(), row.getCommandState(), row.getStatusSource(),
                Boolean.TRUE.equals(row.getActivated()), row.getRemoteUrlKey(), row.getMaturityScore(),
                row.getRecommendationScore(), row.getEnvironmentRiskScore(), row.getPriorityScore(), row.getUa(),
                row.getPlatform(), row.getModel(), row.getOsName(), row.getBrowser(), parse(row.getMaturityJson(), false),
                parse(row.getEnvironmentJson(), false), row.getHitStrategy(), row.getHitStrategyVersion(),
                parse(row.getLatestDecisionJson(), false), parse(row.getLatestSessionJson(), false),
                parse(row.getManualOverrideJson(), false), row.getLastOperatorId(), row.getLastOperationReason(),
                row.getActivationKind(), parse(row.getTagsJson(), true), value(row.getLockVersion(), 0L));
    }

    private JanusStrategyView toStrategy(JanusStrategyRecord row) {
        List<JanusStrategyVersionView> versions = mapper.strategyVersions(row.getStrategyId()).stream()
                .map(this::toVersion).toList();
        return new JanusStrategyView(row.getStrategyId(), row.getName(), row.getDescription(), row.getStatus(),
                value(row.getVersion(), 1), value(row.getPriority(), 0), row.getOwner(), parse(row.getScopeJson(), false),
                parse(row.getRuleTreeJson(), false), parse(row.getActionJson(), false),
                parse(row.getSafeguardsJson(), false), parse(row.getRolloutJson(), false),
                parse(row.getHealthConfigJson(), false), row.getTemplateKey(), versions, row.getCreatedAt(),
                row.getPublishedAt(), value(row.getLockVersion(), 0L));
    }

    private JanusStrategyVersionView toVersion(JanusStrategyVersionRecord row) {
        JsonNode snapshot = parse(row.getSnapshotJson(), false);
        return new JanusStrategyVersionView(value(row.getVersion(), 1), row.getNote(), row.getActorId(),
                row.getCreatedAt(), snapshot.path("ruleTree"), snapshot.path("action"), snapshot, row.getConfigHash());
    }

    private JsonNode parse(String raw, boolean array) {
        if (raw == null || raw.isBlank()) return array ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            return array ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
    }

    private String json(JsonNode node) {
        if (node == null || node.isNull()) return "{}";
        return node.toString();
    }

    private String nullableJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private String arrayJson(JsonNode node) {
        return node == null || !node.isArray() ? "[]" : node.toString();
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String codeOrNull(String value) { return text(value) == null ? null : value.trim().toUpperCase(); }
    private static String lowerOrNull(String value) { return text(value) == null ? null : value.trim().toLowerCase(); }
    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }
    private static long value(Long value, long fallback) { return value == null ? fallback : value; }
    private static int clamp(Integer value, int min, int max, int fallback) {
        return value == null ? fallback : Math.max(min, Math.min(max, value));
    }
}
