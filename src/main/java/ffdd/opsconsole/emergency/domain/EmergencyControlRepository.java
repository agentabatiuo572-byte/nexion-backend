package ffdd.opsconsole.emergency.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EmergencyControlRepository {
    void ensureTables();

    List<Map<String, Object>> geoCountryPolicies();

    /** Current user and wallet exposure grouped by the authoritative user country code. */
    default List<Map<String, Object>> geoCountryImpacts() {
        return List.of();
    }

    /** Recent immutable J2 changes for operator context. */
    default List<Map<String, Object>> geoRecentChanges() {
        return List.of();
    }

    void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator);

    /** Serializes J2 country-list mutations; production uses a database row lock. */
    default void lockGeoCountryMutations() {
    }

    /** Serializes one endpoint policy mutation before its expected-state check. */
    default void lockGeoEndpointMutation(String endpointKey) {
    }

    /** Serializes global edge-source transitions and their fallback window. */
    default void lockGeoEdgeMutation() {
    }

    /**
     * Serializes J3 alert configuration and returns its latest committed values.
     * The production implementation uses locking reads so MySQL REPEATABLE READ
     * cannot validate a queued mutation against a stale snapshot.
     */
    default Map<String, String> tamperConfigForUpdate() {
        lockTamperConfigMutation();
        return Map.of();
    }

    /** @deprecated use {@link #tamperConfigForUpdate()} for authoritative mutation reads. */
    @Deprecated
    default void lockTamperConfigMutation() {
    }

    List<Map<String, Object>> geoEndpointCatalogs();

    Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey);

    List<Map<String, Object>> geoEndpointPolicies();

    void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                    List<String> countryCodes, String source, String reason, String operator);

    List<Map<String, Object>> geoHits();

    Map<String, Integer> geoEndpointHits();

    List<Map<String, Object>> geoEdgeMetrics();

    default void recordGeoBlockEvent(
            String countryCode, String countryName, String endpointKey, String source) {
        // In-memory test repositories may ignore runtime request metrics.
    }

    Optional<String> settingValue(String settingKey);

    void upsertSetting(String settingKey, String settingValue, String valueType, String groupCode, String remark, String operator);

    default boolean compareAndSetSetting(String settingKey, String expectedValue, String newValue, String operator) {
        return false;
    }

    default boolean disableKillSwitchIfEnabled(String settingKey, String legacySettingKey, String operator) {
        return false;
    }

    default boolean claimMissingAutoConfirmation(
            String pendingSettingKey,
            String gateSettingKey,
            String emergencySettingKey,
            String lastChangeSettingKey,
            String operator) {
        return false;
    }

    default boolean repairLegacyLastChange(String lastChangeSettingKey, String operator) {
        return false;
    }

    default boolean completeAutoConfirmation(
            String pendingSettingKey,
            String incidentSettingKey,
            String expectedIncidentId,
            String operator) {
        return false;
    }

    default boolean restoreKillSwitchIfNoPending(
            String settingKey,
            String pendingSettingKey,
            String operator) {
        return false;
    }

    Map<String, Object> tamperTrend(LocalDateTime now);

    List<Map<String, Object>> tamperPaths();

    default List<Map<String, Object>> tamperPaths(LocalDateTime startAt, LocalDateTime endAt) {
        return tamperPaths();
    }

    List<Map<String, Object>> tamperAccounts();

    default List<Map<String, Object>> tamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
        return tamperAccounts();
    }

    default long countTamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
        return tamperAccounts(startAt, endAt, threshold).size();
    }

    default List<Map<String, Object>> pageTamperAccounts(
            LocalDateTime startAt, LocalDateTime endAt, int threshold, int offset, int limit) {
        List<Map<String, Object>> accounts = tamperAccounts(startAt, endAt, threshold);
        int from = Math.max(0, Math.min(offset, accounts.size()));
        int to = Math.max(from, Math.min(from + Math.max(0, limit), accounts.size()));
        return accounts.subList(from, to);
    }

    default List<Map<String, Object>> tamperAccountFrequencyDistribution(
            LocalDateTime startAt, LocalDateTime endAt) {
        return List.of();
    }

    default void recordTamperEvent(TamperEventRecord event) {
        throw new UnsupportedOperationException("TAMPER_EVENT_PROJECTION_NOT_SUPPORTED");
    }

    void createTamperReport(String reportId, String window, boolean masked, String status,
                            Map<String, Object> payload, String operator, String reason);

    List<Map<String, Object>> playbooks();

    /** Fresh committed catalog read used while the caller holds the catalog mutation lock. */
    default List<Map<String, Object>> playbooksIndependent() {
        return playbooks();
    }

    /** Serializes code allocation and globally unique playbook-name validation. */
    default void lockPlaybookCatalogMutations() {
    }

    Optional<Map<String, Object>> playbook(String code);

    /** Locks and returns one complete current playbook definition, including its ordered steps. */
    default Optional<Map<String, Object>> playbookForUpdate(String code) {
        lockPlaybook(code);
        return playbook(code);
    }

    default void lockPlaybook(String code) {
    }

    void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                        String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                        boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                        String operator);

    default void updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                String operator) {
        throw new UnsupportedOperationException("PLAYBOOK_UPDATE_NOT_IMPLEMENTED");
    }

    default boolean updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                   boolean draft, String expectedVersion, String operator) {
        updatePlaybook(code, name, scene, emergency, sla, state, owner, notifyCampaignNo, notifyTemplate,
                rollback, drillRequired, summary, sequence, operator);
        return true;
    }

    void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator);

    Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey);

    /** Fresh committed read used after the playbook lock, outside the caller's RR snapshot. */
    default Optional<Map<String, Object>> executionByIdempotencyKeyIndependent(
            String code, String idempotencyKey) {
        return executionByIdempotencyKey(code, idempotencyKey);
    }

    Optional<Map<String, Object>> execution(String executionId);

    /** Fresh committed read used to resolve concurrent rollback outcomes outside an RR snapshot. */
    default Optional<Map<String, Object>> executionIndependent(String executionId) {
        return execution(executionId);
    }

    List<Map<String, Object>> executions(int limit);

    default long countExecutionsSinceByMode(String mode, LocalDateTime since) {
        return executions(Integer.MAX_VALUE).stream()
                .filter(row -> mode.equals(String.valueOf(row.get("mode"))))
                .count();
    }

    void createExecution(Map<String, Object> row);

    default void createExecutionIndependent(Map<String, Object> row) {
        createExecution(row);
    }

    default void updateExecutionProgressIndependent(
            String executionId,
            List<String> steps,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions) {
        throw new UnsupportedOperationException("execution progress update is not implemented");
    }

    default void updateExecutionProgress(
            String executionId,
            List<String> steps,
            Map<String, Object> notificationDispatch,
            List<Map<String, Object>> domainActions) {
        throw new UnsupportedOperationException("execution progress update is not implemented");
    }

    default boolean claimExecutionRecovery(String executionId, LocalDateTime staleBefore) {
        return false;
    }

    boolean claimExecutionRollback(String executionId);

    boolean completeExecutionRollback(String executionId, LocalDateTime rollbackAt, String reason,
                                      List<Map<String, Object>> rollbackActions);
}
