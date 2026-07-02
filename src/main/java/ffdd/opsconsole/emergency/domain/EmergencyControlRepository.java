package ffdd.opsconsole.emergency.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EmergencyControlRepository {
    void ensureTables();

    List<Map<String, Object>> geoCountryPolicies();

    void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator);

    List<Map<String, Object>> geoEndpointCatalogs();

    Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey);

    List<Map<String, Object>> geoEndpointPolicies();

    void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                    List<String> countryCodes, String source, String reason, String operator);

    List<Map<String, Object>> geoHits();

    Map<String, Integer> geoEndpointHits();

    List<Map<String, Object>> geoEdgeMetrics();

    Optional<String> settingValue(String settingKey);

    void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator);

    Map<String, Object> tamperTrend(LocalDateTime now);

    List<Map<String, Object>> tamperPaths();

    List<Map<String, Object>> tamperAccounts();

    void createTamperReport(String reportId, String window, boolean masked, String status,
                            Map<String, Object> payload, String operator, String reason);

    List<Map<String, Object>> playbooks();

    Optional<Map<String, Object>> playbook(String code);

    void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                        String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                        boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                        String operator);

    void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                        String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                        Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                        String operator);

    void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator);

    Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey);

    Optional<Map<String, Object>> execution(String executionId);

    List<Map<String, Object>> executions(int limit);

    void createExecution(Map<String, Object> row);

    void markExecutionRolledBack(String executionId, LocalDateTime rollbackAt, String reason,
                                 List<Map<String, Object>> rollbackActions);
}
