package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K4CrossDomainClosureContractTest {

    @Test
    void a4RegistersBothServerAuthoritativeK4Facts() {
        String sql = read("scripts/migrations/20260722_k4_cross_domain_event_closure.sql");

        assertThat(sql).contains(
                "risk.score_updated",
                "risk.score_overridden",
                "is_server_authoritative=1",
                "'changed_dimensions','json'",
                "'model_version','string'",
                "'override_score','number'",
                "required_field=VALUES(required_field)");
    }

    @Test
    void runtimeRefreshDetectsEveryCanonicalUpstreamFactWithoutInventingK3OrJ4Inputs() {
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");

        assertThat(mapper).contains(
                "nx_admin_risk_multi_account_cluster",
                "nx_admin_risk_arbitrage_row",
                "nx_kyc_profile",
                "nx_withdrawal_order",
                "nx_risk_signal",
                "updated_at > COALESCE(s.as_of",
                "s.as_of < NOW() - INTERVAL 1 DAY",
                "advanceScoreAsOfToLatestSource",
                "GREATEST(COALESCE(s.as_of,'1970-01-01'),NOW()");
        assertThat(mapper).doesNotContain(
                "WHERE k1.is_deleted=0 AND k1.updated_at > COALESCE(s.as_of",
                "WHERE k2.is_deleted=0 AND k2.updated_at > COALESCE(s.as_of",
                "WHERE c4.user_id=u.id AND c4.is_deleted=0\n                                  AND c4.updated_at > COALESCE(s.as_of",
                "WHERE withdraw_fact.user_id=u.id AND withdraw_fact.is_deleted=0\n                                  AND withdraw_fact.updated_at > COALESCE(s.as_of",
                "WHERE j3.user_id=u.id AND j3.is_deleted=0\n                                  AND j3.updated_at > COALESCE(s.as_of");
        assertThat(mapper).doesNotContain(
                "nx_admin_risk_withdraw_hit h ON h.user_no=s.user_no",
                "nx_admin_incident_sop_execution j4 ON j4.user_no=s.user_no");
    }

    @Test
    void automaticAndCommandRecomputesEmitK4FactsThroughTheSharedOutbox() {
        String service = read("src/main/java/ffdd/opsconsole/risk/application/OpsRiskService.java");
        String initializer = read("src/main/java/ffdd/opsconsole/risk/application/K4ScoreBackfillInitializer.java");
        String publisher = read("src/main/java/ffdd/opsconsole/risk/application/K4ScoreEventPublisher.java");

        assertThat(publisher).contains(
                "risk.score_updated",
                "risk.score_overridden",
                "changedDimensions");
        assertThat(service).contains(
                "K4ScoreEventPublisher.publishScoreUpdated",
                "K4ScoreEventPublisher.publishScoreOverridden");
        assertThat(initializer).contains(
                "EventOutboxService",
                "K4ScoreEventPublisher.publishScoreUpdated");
    }

    @Test
    void automaticProjectionRefreshPreservesManualOverrideUntilAnExplicitReset() {
        String repository = read("src/main/java/ffdd/opsconsole/risk/infrastructure/MybatisRiskOpsRepository.java");
        String initializer = read("src/main/java/ffdd/opsconsole/risk/application/K4ScoreBackfillInitializer.java");
        String service = read("src/main/java/ffdd/opsconsole/risk/application/OpsRiskService.java");

        assertThat(initializer).contains("riskRepository.refreshScoreProjection(");
        assertThat(service).contains("riskRepository.refreshScoreProjection(");
        assertThat(repository).contains(
                "Optional<RiskScoreUserView> refreshScoreProjection(",
                "activeScoreOverride(userNo)",
                "事实源刷新（保留人工覆盖）");
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
