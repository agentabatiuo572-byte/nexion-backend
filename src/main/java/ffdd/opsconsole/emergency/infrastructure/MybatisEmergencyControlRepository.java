package ffdd.opsconsole.emergency.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.TamperEventRecord;
import ffdd.opsconsole.emergency.mapper.EmergencyControlMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisEmergencyControlRepository implements EmergencyControlRepository {
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_STRING_TYPE = new TypeReference<>() {
    };
    private static final Set<String> J4_STEP_STATUSES = Set.of(
            "pending", "running", "done", "failed", "skipped", "rolled_back", "recovering");

    private final EmergencyControlMapper mapper;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean tablesEnsured = new AtomicBoolean(false);

    @PostConstruct
    void initializeSchema() {
        ensureTables();
    }

    @Override
    public void ensureTables() {
        if (tablesEnsured.get()) {
            return;
        }
        synchronized (tablesEnsured) {
            if (tablesEnsured.get()) {
                return;
            }
            mapper.createGeoCountryPolicyTable();
            mapper.createGeoEndpointCatalogTable();
            mapper.createGeoEndpointPolicyTable();
            mapper.createGeoBlockEventTable();
            mapper.createTamperEventTable();
            mapper.createTamperReportTable();
            mapper.createControlSettingTable();
            mapper.createSopPlaybookTable();
            mapper.createSopActionTable();
            mapper.createSopExecutionTable();
            tablesEnsured.set(true);
        }
    }

    @Override
    public List<Map<String, Object>> geoCountryPolicies() {
        return mapper.geoCountryPolicies();
    }

    @Override
    public List<Map<String, Object>> geoCountryImpacts() {
        return mapper.geoCountryImpacts();
    }

    @Override
    public List<Map<String, Object>> geoRecentChanges() {
        return mapper.geoRecentChanges();
    }

    @Override
    public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        ensureTables();
        mapper.upsertGeoCountryPolicy(countryCode, countryName, status, reason, operator);
    }

    @Override
    public void lockGeoCountryMutations() {
        ensureTables();
        mapper.ensureGeoCountryMutationLock();
        mapper.lockGeoCountryMutations();
    }

    @Override
    public void lockGeoEndpointMutation(String endpointKey) {
        ensureTables();
        mapper.ensureGeoEndpointMutationLock(endpointKey);
        mapper.lockGeoEndpointMutation(endpointKey);
    }

    @Override
    public void lockGeoEdgeMutation() {
        ensureTables();
        mapper.ensureGeoEdgeMutationLock();
        mapper.lockGeoEdgeMutation();
    }

    @Override
    public Map<String, String> tamperConfigForUpdate() {
        ensureTables();
        mapper.ensureTamperConfigMutationLock();
        mapper.lockTamperConfigMutation();
        Map<String, String> config = new LinkedHashMap<>();
        mapper.tamperConfigForUpdate().forEach(row -> config.put(
                String.valueOf(row.get("settingKey")),
                String.valueOf(row.get("settingValue"))));
        return config;
    }

    @Override
    public List<Map<String, Object>> geoEndpointCatalogs() {
        return mapper.geoEndpointCatalogs();
    }

    @Override
    public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
        return Optional.ofNullable(mapper.geoEndpointCatalog(endpointKey));
    }

    @Override
    public List<Map<String, Object>> geoEndpointPolicies() {
        return mapper.geoEndpointPolicies();
    }

    @Override
    public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                           List<String> countryCodes, String source, String reason, String operator) {
        ensureTables();
        mapper.softDeleteGeoEndpointPolicies(endpointKey);
        for (String countryCode : countryCodes == null ? List.<String>of() : countryCodes) {
            mapper.insertGeoEndpointPolicy(endpointKey, endpointPath, label, biz, domain, countryCode, source, reason, operator);
        }
    }

    @Override
    public List<Map<String, Object>> geoHits() {
        return mapper.geoHits();
    }

    @Override
    public Map<String, Integer> geoEndpointHits() {
        return mapper.geoEndpointHits().stream()
                .filter(row -> row.get("endpointKey") != null)
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("endpointKey")),
                        row -> intValue(row.get("count")),
                        Integer::sum,
                        LinkedHashMap::new));
    }

    @Override
    public List<Map<String, Object>> geoEdgeMetrics() {
        return mapper.geoEdgeMetrics();
    }

    @Override
    public void recordGeoBlockEvent(String countryCode, String countryName, String endpointKey, String source) {
        ensureTables();
        mapper.insertGeoBlockEvent(countryCode, countryName, endpointKey, source);
    }

    @Override
    public Optional<String> settingValue(String settingKey) {
        return Optional.ofNullable(mapper.settingValue(settingKey)).filter(StringUtils::hasText);
    }

    @Override
    public void upsertSetting(
            String settingKey,
            String settingValue,
            String valueType,
            String groupCode,
            String remark,
            String operator) {
        ensureTables();
        mapper.upsertSetting(settingKey, settingValue, valueType, groupCode, remark, operator);
    }

    @Override
    public boolean compareAndSetSetting(String settingKey, String expectedValue, String newValue, String operator) {
        ensureTables();
        return mapper.compareAndSetSetting(settingKey, expectedValue, newValue, operator) == 1;
    }

    @Override
    public boolean disableKillSwitchIfEnabled(String settingKey, String legacySettingKey, String operator) {
        ensureTables();
        if (mapper.disableExistingSettingIfEnabled(settingKey, legacySettingKey, operator) == 1) {
            return true;
        }
        if (mapper.insertDisabledSettingIfEffectiveDefaultEnabled(settingKey, legacySettingKey, operator) == 1) {
            return true;
        }
        // The primary row may have been materialized as enabled by a concurrent restore
        // between the first UPDATE and INSERT IGNORE. Retry the atomic UPDATE so an
        // emergency disable cannot be lost on an initially absent primary row.
        return mapper.disableExistingSettingIfEnabled(settingKey, legacySettingKey, operator) == 1;
    }

    @Override
    public boolean claimMissingAutoConfirmation(
            String pendingSettingKey,
            String gateSettingKey,
            String emergencySettingKey,
            String lastChangeSettingKey,
            String operator) {
        ensureTables();
        if (mapper.restoreDeletedAutoConfirmation(
                pendingSettingKey, gateSettingKey, emergencySettingKey, lastChangeSettingKey, operator) == 1) {
            return true;
        }
        return mapper.claimMissingAutoConfirmation(
                pendingSettingKey, gateSettingKey, emergencySettingKey, lastChangeSettingKey, operator) == 1;
    }

    @Override
    public boolean repairLegacyLastChange(String lastChangeSettingKey, String operator) {
        ensureTables();
        return mapper.repairLegacyLastChange(lastChangeSettingKey, operator) == 1;
    }

    @Override
    public boolean completeAutoConfirmation(
            String pendingSettingKey,
            String incidentSettingKey,
            String expectedIncidentId,
            String operator) {
        ensureTables();
        return mapper.completeAutoConfirmation(
                pendingSettingKey, incidentSettingKey, expectedIncidentId, operator) == 1;
    }

    @Override
    public boolean restoreKillSwitchIfNoPending(
            String settingKey,
            String pendingSettingKey,
            String operator) {
        ensureTables();
        if (mapper.restoreKillSwitchIfNoPending(settingKey, pendingSettingKey, operator) == 1) {
            return true;
        }
        return mapper.insertEnabledKillSwitchIfNoPending(settingKey, pendingSettingKey, operator) == 1;
    }

    @Override
    public Map<String, Object> tamperTrend(LocalDateTime now) {
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("24h", trendWindow(mapper.tamperTrend24h(now.minusHours(24), now)));
        trend.put("7d", trendWindow(mapper.tamperTrendDaily(now.minusDays(7), now)));
        trend.put("30d", trendWindow(mapper.tamperTrendDaily(now.minusDays(30), now)));
        return trend;
    }

    @Override
    public List<Map<String, Object>> tamperPaths() {
        LocalDateTime now = LocalDateTime.now();
        return tamperPaths(now.minusHours(24), now);
    }

    @Override
    public List<Map<String, Object>> tamperPaths(LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.tamperPaths(startAt, endAt);
    }

    @Override
    public List<Map<String, Object>> tamperAccounts() {
        LocalDateTime now = LocalDateTime.now();
        return tamperAccounts(now.minusHours(24), now, 10);
    }

    @Override
    public List<Map<String, Object>> tamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
        return normalizeTamperAccounts(mapper.tamperAccounts(startAt, endAt, threshold));
    }

    @Override
    public long countTamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
        return mapper.countTamperAccounts(startAt, endAt, threshold);
    }

    @Override
    public List<Map<String, Object>> pageTamperAccounts(
            LocalDateTime startAt, LocalDateTime endAt, int threshold, int offset, int limit) {
        return normalizeTamperAccounts(mapper.pageTamperAccounts(startAt, endAt, threshold, offset, limit));
    }

    private List<Map<String, Object>> normalizeTamperAccounts(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> next = new LinkedHashMap<>(row);
                    next.put("paths", splitCsv(String.valueOf(next.getOrDefault("pathCsv", ""))));
                    next.remove("pathCsv");
                    return next;
                })
                .toList();
    }

    @Override
    public List<Map<String, Object>> tamperAccountFrequencyDistribution(
            LocalDateTime startAt, LocalDateTime endAt) {
        return mapper.tamperAccountFrequencyDistribution(startAt, endAt);
    }

    @Override
    public void recordTamperEvent(TamperEventRecord event) {
        ensureTables();
        mapper.insertTamperEvent(event);
    }

    @Override
    public void createTamperReport(String reportId, String window, boolean masked, String status,
                                   Map<String, Object> payload, String operator, String reason) {
        ensureTables();
        mapper.insertTamperReport(reportId, window, masked, status, toJson(payload), operator, reason);
    }

    @Override
    public List<Map<String, Object>> playbooks() {
        Map<String, List<Map<String, Object>>> steps = mapper.playbookSteps().stream()
                .collect(Collectors.groupingBy(
                        row -> String.valueOf(row.get("code")),
                        LinkedHashMap::new,
                        Collectors.toList()));
        return mapper.playbooks().stream()
                .map(row -> withSequence(row, steps.getOrDefault(String.valueOf(row.get("code")), List.of())))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Map<String, Object>> playbooksIndependent() {
        return playbooks();
    }

    @Override
    public void lockPlaybookCatalogMutations() {
        ensureTables();
        mapper.ensurePlaybookCatalogMutationLock();
        mapper.lockPlaybookCatalogMutations();
    }

    @Override
    public Optional<Map<String, Object>> playbook(String code) {
        return playbooks().stream()
                .filter(row -> String.valueOf(row.get("code")).equalsIgnoreCase(code))
                .findFirst();
    }

    @Override
    public Optional<Map<String, Object>> playbookForUpdate(String code) {
        ensureTables();
        Map<String, Object> row = mapper.playbookForUpdate(code);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(withSequence(row, mapper.playbookStepsForUpdate(code)));
    }

    @Override
    public void lockPlaybook(String code) {
        ensureTables();
        mapper.lockPlaybook(code);
    }

    @Override
    public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                               String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                               boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                               String operator) {
        ensureTables();
        mapper.insertPlaybook(code, name, scene, emergency, sla, state, owner, notifyCampaignNo, notifyTemplate,
                rollback, drillRequired, draft, operator);
        replaceSteps(code, sequence);
    }

    @Override
    public boolean updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                  String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                  Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                  boolean draft, String expectedVersion, String operator) {
        ensureTables();
        int updated = mapper.updatePlaybook(code, emptyToNull(name), emptyToNull(scene), emergency, emptyToNull(sla), emptyToNull(state),
                emptyToNull(owner), notifyCampaignNo, notifyTemplate, emptyToNull(rollback),
                drillRequired, emptyToNull(summary), draft, expectedVersion, operator);
        if (updated > 0 && sequence != null) {
            replaceSteps(code, sequence);
        }
        return updated > 0;
    }

    @Override
    public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
        ensureTables();
        mapper.markPlaybookDrilled(code, drillAt, operator);
    }

    @Override
    public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
        return Optional.ofNullable(mapper.executionByIdempotencyKey(code, idempotencyKey))
                .map(this::inflateExecution);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Map<String, Object>> executionByIdempotencyKeyIndependent(
            String code, String idempotencyKey) {
        return Optional.ofNullable(mapper.executionByIdempotencyKey(code, idempotencyKey))
                .map(this::inflateExecution);
    }

    @Override
    public Optional<Map<String, Object>> execution(String executionId) {
        return Optional.ofNullable(mapper.execution(executionId)).map(this::inflateExecution);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Map<String, Object>> executionIndependent(String executionId) {
        return Optional.ofNullable(mapper.execution(executionId)).map(this::inflateExecution);
    }

    @Override
    public List<Map<String, Object>> executions(int limit) {
        return mapper.executions(limit).stream().map(this::inflateExecution).toList();
    }

    @Override
    public long countExecutionsSinceByMode(String mode, LocalDateTime since) {
        ensureTables();
        return mapper.countExecutionsSinceByMode(mode, since);
    }

    @Override
    public void createExecution(Map<String, Object> row) {
        ensureTables();
        mapper.insertExecution(
                String.valueOf(row.get("executionId")),
                String.valueOf(row.get("code")),
                String.valueOf(row.get("name")),
                String.valueOf(row.get("trigger")),
                String.valueOf(row.get("mode")),
                String.valueOf(row.get("operator")),
                String.valueOf(row.get("roleGate")),
                String.valueOf(row.getOrDefault("idempotencyKey", "")),
                toJson(row.get("steps")),
                toJson(row.get("notificationDispatch")),
                toJson(row.get("domainActions")),
                String.valueOf(row.getOrDefault("rollback", "")));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createExecutionIndependent(Map<String, Object> row) {
        createExecution(row);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateExecutionProgressIndependent(
            String executionId,
            List<String> steps,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions) {
        updateExecutionProgress(executionId, steps, notificationDispatch, domainActions);
    }

    @Override
    public void updateExecutionProgress(
            String executionId,
            List<String> steps,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions) {
        ensureTables();
        if (mapper.updateExecutionProgress(
                executionId, toJson(steps), toJson(notificationDispatch), toJson(domainActions)) != 1) {
            throw new IllegalStateException("J4_EXECUTION_PROGRESS_NOT_FOUND");
        }
    }

    @Override
    public boolean claimExecutionRecovery(String executionId, LocalDateTime staleBefore) {
        ensureTables();
        return mapper.claimExecutionRecovery(executionId, staleBefore) == 1;
    }

    @Override
    public boolean claimExecutionRollback(String executionId) {
        ensureTables();
        return mapper.claimExecutionRollback(executionId) == 1;
    }

    @Override
    public boolean completeExecutionRollback(String executionId, LocalDateTime rollbackAt, String reason,
                                             List<Map<String, Object>> rollbackActions) {
        ensureTables();
        return mapper.completeExecutionRollback(
                executionId, rollbackAt, reason, toJson(rollbackActions)) == 1;
    }

    private void replaceSteps(String code, List<Map<String, Object>> sequence) {
        mapper.softDeleteSteps(code);
        int order = 0;
        for (Map<String, Object> step : sequence == null ? List.<Map<String, Object>>of() : sequence) {
            order++;
            mapper.insertStep(
                    code,
                    order,
                    String.valueOf(step.getOrDefault("domain", "")),
                    String.valueOf(step.getOrDefault("action", "")),
                    Boolean.TRUE.equals(step.get("approve")),
                    String.valueOf(step.getOrDefault("ref", "")));
        }
    }

    private Map<String, Object> withSequence(Map<String, Object> row, List<Map<String, Object>> steps) {
        Map<String, Object> next = new LinkedHashMap<>(row);
        next.put("sequence", steps.stream()
                .sorted(Comparator.comparing(step -> Integer.parseInt(String.valueOf(step.getOrDefault("stepOrder", "0")))))
                .map(step -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("domain", step.get("domain"));
                    item.put("action", step.get("action"));
                    item.put("approve", Boolean.TRUE.equals(step.get("approve"))
                            || "1".equals(String.valueOf(step.get("approve"))));
                    item.put("ref", step.get("ref"));
                    return item;
                })
                .toList());
        return next;
    }

    private Map<String, Object> inflateExecution(Map<String, Object> row) {
        Map<String, Object> next = new LinkedHashMap<>(row);
        next.put("timestamp", String.valueOf(row.getOrDefault("timestamp", "")));
        next.put("steps", readRequiredStringList(row.get("stepsJson"), "step_status_json"));
        next.put("notificationDispatch", readObject(row.get("notificationJson"), "notification_json"));
        next.put("domainActions", readRequiredMapList(row.get("domainActionsJson"), "domain_action_json"));
        next.put("rollbackActions", readOptionalMapList(row.get("rollbackActionsJson"), "rollback_action_json"));
        next.remove("stepsJson");
        next.remove("notificationJson");
        next.remove("domainActionsJson");
        next.remove("rollbackActionsJson");
        return next;
    }

    private Map<String, Object> trendWindow(List<Map<String, Object>> rows) {
        List<Object> labels = new ArrayList<>();
        List<Object> points = new ArrayList<>();
        int max = 0;
        for (Map<String, Object> row : rows) {
            Object label = row.get("label");
            Object count = row.get("count");
            labels.add(label);
            points.add(count);
            try {
                max = Math.max(max, Integer.parseInt(String.valueOf(count)));
            } catch (RuntimeException ignored) {
                // Keep a best-effort chart envelope; bad database values become zero.
            }
        }
        return Map.of("points", points, "max", max, "labels", labels);
    }

    private List<String> readRequiredStringList(Object json, String field) {
        if (json == null || !StringUtils.hasText(String.valueOf(json))) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
        }
        try {
            List<String> value = objectMapper.readValue(String.valueOf(json), LIST_STRING_TYPE);
            if (value == null || value.isEmpty()
                    || value.stream().anyMatch(item -> !StringUtils.hasText(item) || !J4_STEP_STATUSES.contains(item))) {
                throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field, ex);
        }
    }

    private List<Map<String, Object>> readRequiredMapList(Object json, String field) {
        if (json == null || !StringUtils.hasText(String.valueOf(json))) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
        }
        try {
            List<Map<String, Object>> value = objectMapper.readValue(String.valueOf(json), LIST_MAP_TYPE);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field, ex);
        }
    }

    private List<Map<String, Object>> readOptionalMapList(Object json, String field) {
        if (json == null || !StringUtils.hasText(String.valueOf(json))) {
            return List.of();
        }
        try {
            List<Map<String, Object>> value = objectMapper.readValue(String.valueOf(json), LIST_MAP_TYPE);
            return value == null ? List.of() : value;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(Object json, String field) {
        if (json == null || !StringUtils.hasText(String.valueOf(json))) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
        }
        try {
            Object value = objectMapper.readValue(String.valueOf(json), Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("J4_EXECUTION_JSON_CORRUPT:" + field, ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
