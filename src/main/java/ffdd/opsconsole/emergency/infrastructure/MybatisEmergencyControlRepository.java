package ffdd.opsconsole.emergency.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.mapper.EmergencyControlMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisEmergencyControlRepository implements EmergencyControlRepository {
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };

    private final EmergencyControlMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void ensureTables() {
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
    }

    @Override
    public List<Map<String, Object>> geoCountryPolicies() {
        return mapper.geoCountryPolicies();
    }

    @Override
    public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
        ensureTables();
        mapper.upsertGeoCountryPolicy(countryCode, countryName, status, reason, operator);
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
    public Map<String, Object> tamperTrend(LocalDateTime now) {
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("24h", trendWindow(mapper.tamperTrend24h(now.minusHours(24), now)));
        trend.put("7d", trendWindow(mapper.tamperTrendDaily(now.minusDays(7), now)));
        trend.put("30d", trendWindow(mapper.tamperTrendDaily(now.minusDays(30), now)));
        return trend;
    }

    @Override
    public List<Map<String, Object>> tamperPaths() {
        return mapper.tamperPaths();
    }

    @Override
    public List<Map<String, Object>> tamperAccounts() {
        return mapper.tamperAccounts().stream()
                .map(row -> {
                    Map<String, Object> next = new LinkedHashMap<>(row);
                    next.put("paths", splitCsv(String.valueOf(next.getOrDefault("pathCsv", ""))));
                    next.remove("pathCsv");
                    return next;
                })
                .toList();
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
    public Optional<Map<String, Object>> playbook(String code) {
        return playbooks().stream()
                .filter(row -> String.valueOf(row.get("code")).equalsIgnoreCase(code))
                .findFirst();
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
    public void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                               String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                               Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                               String operator) {
        ensureTables();
        mapper.updatePlaybook(code, emptyToNull(name), emptyToNull(scene), emergency, emptyToNull(sla), emptyToNull(state),
                emptyToNull(owner), emptyToNull(notifyCampaignNo), emptyToNull(notifyTemplate), emptyToNull(rollback),
                drillRequired, emptyToNull(summary), operator);
        if (sequence != null) {
            replaceSteps(code, sequence);
        }
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
    public Optional<Map<String, Object>> execution(String executionId) {
        return Optional.ofNullable(mapper.execution(executionId)).map(this::inflateExecution);
    }

    @Override
    public List<Map<String, Object>> executions(int limit) {
        return mapper.executions(limit).stream().map(this::inflateExecution).toList();
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
    public void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                        List<Map<String, Object>> rollbackActions) {
        ensureTables();
        mapper.markExecutionRolledBack(executionId, rollbackAt, reason, toJson(rollbackActions));
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
        next.put("steps", readList(row.get("stepsJson")));
        next.put("notificationDispatch", readObject(row.get("notificationJson")));
        next.put("domainActions", readList(row.get("domainActionsJson")));
        next.put("rollbackActions", readList(row.get("rollbackActionsJson")));
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

    private List<Map<String, Object>> readList(Object json) {
        if (!StringUtils.hasText(String.valueOf(json))) {
            return List.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(json), LIST_MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(Object json) {
        if (!StringUtils.hasText(String.valueOf(json))) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(String.valueOf(json), Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (JsonProcessingException ex) {
            return Map.of();
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
