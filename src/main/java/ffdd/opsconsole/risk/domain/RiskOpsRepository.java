package ffdd.opsconsole.risk.domain;

import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RiskOpsRepository {
    Map<String, Object> overview();

    List<RiskCaseView> search(Long userId, String status, String decision, int limit);

    PageResult<RiskCaseView> pageCases(RiskCaseQueryRequest request);

    Optional<RiskCaseView> findByCaseNo(String caseNo);

    void updateDecision(String caseNo, String decision, String reason, String operator);

    void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator);

    default TamperProjection projectTamperSignal(
            String signalNo, Long userId, String userNo, String evidence, int eventCount,
            boolean feedK4, String operator) {
        throw new UnsupportedOperationException("TAMPER_PROJECTION_NOT_IMPLEMENTED");
    }

    default TamperRadarSnapshot tamperRadarSnapshot(java.time.LocalDateTime since) {
        return new TamperRadarSnapshot(0, 0, "");
    }

    RiskCaseView createManualReviewCase(String caseNo, Long userId, String bizType, String bizNo, String reason, int riskScore, String ruleCodes, String ruleSnapshot, String operator);

    List<RiskRuleView> withdrawRules();

    PageResult<RiskRuleView> pageWithdrawRules(int pageNum, int pageSize);

    Optional<RiskRuleView> findWithdrawRule(String ruleId);

    List<RiskWithdrawCandidateView> withdrawRuleCandidates(int limit);

    RiskRuleView createWithdrawRule(
            String ruleId, String dimension, String conditionText, String action,
            String state, int priority, String operator);

    Optional<RiskRuleView> updateWithdrawRuleState(String ruleId, long expectedVersion, String state);

    Optional<RiskRuleView> updateWithdrawRuleConfiguration(
            String ruleId, long expectedVersion, String conditionText, String action, int priority);

    List<RiskRouteCountView> withdrawRouteCounts();

    List<RiskRuleHitView> withdrawRuleHits(String action, int limit);

    PageResult<RiskRuleHitView> pageWithdrawRuleHits(String action, int pageNum, int pageSize);

    void recordWithdrawRuleHit(String withdrawalNo, String userNo, BigDecimal amount, RiskRuleView rule);

    List<RiskArbitrageStatView> arbitrageStats();

    List<RiskArbitrageParamView> arbitrageParams();

    Optional<RiskArbitrageParamView> updateArbitrageParam(String key, long expectedVersion, String value);

    List<RiskArbitrageRowView> arbitrageRows();

    Optional<RiskArbitrageRowView> findArbitrageRow(String rowId);

    Optional<RiskArbitrageRowView> updateArbitrageDisposition(String rowId, long expectedVersion, String disposition);

    List<RiskScoreDimensionView> scoringDimensions();

    default Optional<RiskScoreModelView> activeScoringModel() {
        return Optional.empty();
    }

    default Optional<RiskScoreModelView> draftScoringModel() {
        return Optional.empty();
    }

    default List<RiskScoreModelView> scoringModels() {
        return List.of();
    }

    default Optional<RiskScoreModelView> scoringModel(long modelVersion) {
        return Optional.empty();
    }

    default Optional<RiskScoreModelView> saveScoringModelDraft(
            long expectedVersion, RiskScoringModelDraftRequest request, String operator) {
        return Optional.empty();
    }

    default Optional<RiskScoreModelView> publishScoringModel(
            long expectedVersion, String reason, String operator) {
        return Optional.empty();
    }

    List<RiskScoreDimensionView> updateScoringWeights(Map<String, Integer> weights);

    RiskScoreConfigView scoringConfig();

    RiskScoreConfigView updateScoringConfig(String key, String value);

    List<RiskScoreDistributionView> scoringDistribution();

    List<RiskScoreOverrideView> scoreOverrides();

    PageResult<RiskScoreOverrideView> pageScoreOverrides(int pageNum, int pageSize);

    long countActiveScoreOverrides();

    Optional<RiskScoreUserView> findScoreUser(String userNo);

    default List<RiskScoreHistoryView> scoreHistory(String userNo, int limit) {
        return List.of();
    }

    List<RiskScoreUserSearchView> searchScoreUsers(String keyword, int limit);

    Optional<RiskScoreOverrideView> overrideScore(String userNo, int score, String reason, String operator);

    default Optional<RiskScoreOverrideView> overrideScore(
            String userNo, long expectedVersion, int score, String reason, String operator) {
        return Optional.empty();
    }

    Optional<RiskScoreUserView> recomputeScore(String userNo);

    default Optional<RiskScoreRawInput> scoringInput(String userNo) {
        return Optional.empty();
    }

    default Optional<RiskScoreUserView> recomputeScore(
            String userNo,
            long expectedVersion,
            RiskScoreModelView model,
            int modelScore,
            List<RiskScoreContributionView> contributions) {
        return Optional.empty();
    }

    default List<String> scoreUserNos() {
        return List.of();
    }

    default List<String> scoreUserNosNeedingProjection(long modelVersion, int limit) {
        String expected = "k4-v" + modelVersion;
        return scoreUserNos().stream()
                .filter(userNo -> findScoreUser(userNo)
                        .map(user -> !java.util.Objects.equals(user.modelVersion(), expected))
                        .orElse(false))
                .limit(Math.max(1, limit))
                .toList();
    }

    default long countScoreUsersNeedingProjection(long modelVersion) {
        String expected = "k4-v" + modelVersion;
        return scoreUserNos().stream()
                .filter(userNo -> findScoreUser(userNo)
                        .map(user -> !java.util.Objects.equals(user.modelVersion(), expected))
                        .orElse(false))
                .count();
    }

    default int synchronizeScoringUsers() {
        return 0;
    }

    Map<String, Object> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                             Integer whitelistPageNum, Integer whitelistPageSize);

    default Map<String, Object> multiAccountOverview(
            Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
            String clusterStatus, String clusterSort,
            Integer whitelistPageNum, Integer whitelistPageSize) {
        return multiAccountOverview(clusterPageNum, clusterPageSize, clusterLayer, whitelistPageNum, whitelistPageSize);
    }

    Map<String, Object> updateMultiAccountParam(String key, String value);

    default Optional<String> multiAccountParamValue(String key) {
        return Optional.empty();
    }

    boolean updateMultiAccountClusterStatus(String clusterId, String status, String reason, String operator);

    default Optional<MultiAccountClusterState> multiAccountClusterState(String clusterId) {
        return Optional.empty();
    }

    default boolean updateMultiAccountClusterStatus(
            String clusterId, String expectedStatus, long expectedVersion,
            String status, String reason, String operator) {
        return updateMultiAccountClusterStatus(clusterId, status, reason, operator);
    }

    default boolean updateMultiAccountClusterReviewNote(
            String clusterId, long expectedVersion, String reason, String operator) {
        return false;
    }

    void upsertIpWhitelist(String cidr, String note, String operator, String expireText);

    boolean disableIpWhitelist(String cidr, String operator);

    default Optional<IpWhitelistState> ipWhitelistState(String cidr) {
        return Optional.empty();
    }

    default List<MultiAccountSignalFact> multiAccountSignalFacts() {
        return List.of();
    }

    default Set<String> activeIpWhitelistCidrs() {
        return Set.of();
    }

    default Map<String, String> multiAccountConfigValues() {
        return Map.of();
    }

    default void upsertMultiAccountProjections(List<MultiAccountClusterProjection> projections) {
    }

    default void retireMissingDetectedClusters(Set<String> activeClusterIds) {
    }

    default void clearWhitelistedDetectedClusters(Set<String> clusterIds) {
    }

    default Map<String, Object> kycReviewOverview() {
        return kycReviewOverview(1, 5, null);
    }

    Map<String, Object> kycReviewOverview(Integer ticketPageNum, Integer ticketPageSize, String ticketFilter);

    Optional<Map<String, Object>> updateKycReviewParam(String key, String value, long expectedVersion);

    boolean updateKycReviewTicketStatus(String ticketId, String status, long expectedVersion,
                                        String reasonCode, String reason, String operator);

    Optional<KycReviewTicketContext> findKycReviewTicket(String ticketId);

    Optional<KycReviewTicketContext> findOpenKycReviewTicketByUser(String userNo);

    boolean mergeOpenKycReviewTicket(String ticketId, long expectedVersion, String reason, String operator);

    void linkKycReviewSource(String ticketId, String sourceDomain, String sourceNo);

    List<KycReviewSource> kycReviewSources(String ticketId);

    Map<String, Object> kycAlertSubscription(String operator);

    List<Map<String, Object>> kycAlerts(List<String> alertTypes);

    Optional<Map<String, Object>> updateKycAlertSubscription(
            String operator, List<String> alertTypes, List<String> channels, long expectedVersion);

    int generateOverdueKycAlerts();

    int generateLargeWithdrawalBurstKycAlerts();

    void createManualKycReviewTicket(String ticketId, String userNo, String reason, String operator);

    int kycReviewTriggerScore();

    int kycLargeWithdrawReviewUsdt();

    int kycLargeExchangeReviewUsdt();

    int kycReviewSlaDays();

    boolean hasOpenKycReviewTicket(String userNo);

    void createScoreTriggeredKycReviewTicket(String ticketId, String userNo, int score, int threshold, String reason, String operator);

    void createLargeWithdrawalKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String withdrawalNo,
                                              String kycStatus, String reason, String operator);

    void createLargeExchangeKycReviewTicket(String ticketId, String userNo, BigDecimal amountUsdt, String exchangeNo,
                                            String kycStatus, String reason, String operator);

    record KycReviewSource(String sourceDomain, String sourceNo) {
    }

    record TamperProjection(boolean k4Accepted, int k4Delta, boolean b5Accepted) {
    }

    record TamperRadarSnapshot(long signalCount, long accountCount, String latestAt) {
    }

    record MultiAccountSignalFact(
            long userId,
            String userNo,
            LocalDateTime joinedAt,
            Long sponsorUserId,
            Boolean gotWelcomeGift,
            BigDecimal depositCumulativeUsdt,
            String accountStatus,
            String layer,
            String rawKey,
            String maskedKey) {
    }

    record MultiAccountEdge(String from, String to, String layer, double weight) {
    }

    record MultiAccountNode(
            String userNo,
            LocalDateTime joinedAt,
            String sponsorUserNo,
            Boolean gotWelcomeGift,
            BigDecimal depositCumulativeUsdt,
            String accountStatus) {
    }

    record MultiAccountClusterProjection(
            String clusterId,
            String maskedKey,
            String layer,
            String layerLabel,
            int accountCount,
            double strength,
            String spanText,
            String note,
            String evidenceFingerprint,
            List<Long> affectedUserIds,
            List<MultiAccountNode> nodes,
            List<MultiAccountEdge> edges,
            List<List<String>> giftDuplicates,
            boolean thresholdHit) {
        public MultiAccountClusterProjection withClusterId(String nextClusterId) {
            List<List<String>> movedGifts = giftDuplicates.stream().map(row -> {
                if (row.isEmpty() || !clusterId.equals(row.get(0))) return row;
                java.util.ArrayList<String> copy = new java.util.ArrayList<>(row);
                copy.set(0, nextClusterId);
                return List.copyOf(copy);
            }).toList();
            return new MultiAccountClusterProjection(
                    nextClusterId, maskedKey, layer, layerLabel, accountCount, strength, spanText, note,
                    evidenceFingerprint, affectedUserIds, nodes, edges, movedGifts, thresholdHit);
        }

        public MultiAccountClusterProjection withThresholdHit(boolean nextThresholdHit) {
            return new MultiAccountClusterProjection(
                    clusterId, maskedKey, layer, layerLabel, accountCount, strength, spanText, note,
                    evidenceFingerprint, affectedUserIds, nodes, edges, giftDuplicates, nextThresholdHit);
        }
    }

    record MultiAccountClusterState(
            String clusterId,
            String status,
            String layer,
            double strength,
            List<String> affectedUserIds,
            long version,
            String evidenceFingerprint,
            boolean thresholdHit) {
        public MultiAccountClusterState(
                String clusterId, String status, String layer, double strength,
                List<String> affectedUserIds, long version, String evidenceFingerprint) {
            this(clusterId, status, layer, strength, affectedUserIds, version, evidenceFingerprint, false);
        }

        public MultiAccountClusterState(
                String clusterId, String status, String layer, double strength,
                List<String> affectedUserIds, long version) {
            this(clusterId, status, layer, strength, affectedUserIds, version, null, false);
        }
    }

    record IpWhitelistState(
            String cidr,
            String note,
            String operator,
            String expireText,
            boolean active) {
    }
}
