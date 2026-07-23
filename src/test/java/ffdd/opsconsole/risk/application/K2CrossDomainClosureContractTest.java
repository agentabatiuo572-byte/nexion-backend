package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K2CrossDomainClosureContractTest {

    @Test
    void trialProjectionConsumesOnlyRegisteredServerAuthoritativeH2EventsAndK1Membership() {
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");

        assertThat(mapper).contains(
                "event_name = 'trial.started'",
                "e.analytics_event = 1",
                "e.schema_registered = 1",
                "e.is_server_authoritative = 1",
                "JSON_SEARCH(c.nodes_json",
                "K2-H2-U",
                "upsertH2TrialCycleRows");
        assertThat(mapper).doesNotContain("localStorage");
    }

    @Test
    void k2SignalsUseTheSharedRiskTableAndA4OutboxWithoutExecutingF4Disqualification() {
        String service = read("src/main/java/ffdd/opsconsole/risk/application/OpsRiskService.java");
        String team = read("src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java");
        String teamService = read("src/main/java/ffdd/opsconsole/team/application/OpsTeamService.java");

        assertThat(service).contains(
                "recordSignalIfAbsent",
                "eventOutboxService.publish",
                "risk.arbitrage_suspected",
                "risk.trial_cycle_detected",
                "risk.leaderboard_velocity_flagged");
        assertThat(team).contains(
                "signal_type = 'risk.leaderboard_velocity_flagged'",
                ") > 0 THEN 'disqualified'",
                ") > 0 THEN 'flagged'");
        assertThat(teamService).contains(
                "\"disqualified\".equalsIgnoreCase(leaderboardStatus)");
        assertThat(teamService).doesNotContain(
                "Set.of(\"disqualified\", \"flagged\").contains(leaderboardStatus");
        assertThat(service).doesNotContain("insertLeaderboardAction");
    }

    @Test
    void h8ResolvesClusterOnlyK2BlocksThroughCanonicalK1Nodes() {
        String mapper = read("src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java");

        assertThat(mapper).contains(
                "risk_cluster.cluster_id = risk.cluster_id",
                "JSON_VALID(risk_cluster.nodes_json) = 1",
                "JSON_SEARCH(risk_cluster.nodes_json");
    }

    @Test
    void migrationRegistersExactServerAuthoritativeSchemas() {
        String sql = read("scripts/migrations/20260722_k2_cross_domain_signal_closure.sql");

        assertThat(sql).contains(
                "risk.arbitrage_suspected",
                "risk.trial_cycle_detected",
                "risk.leaderboard_velocity_flagged",
                "is_server_authoritative=1",
                "'subject_user_ids','json'",
                "required_field=VALUES(required_field)");
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
