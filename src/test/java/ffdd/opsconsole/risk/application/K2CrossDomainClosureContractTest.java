package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class K2CrossDomainClosureContractTest {

    private static final Pattern TRUNCATING_USER_NUMBER = Pattern.compile(
            "(?i)CONCAT\\s*\\(\\s*'U'\\s*,\\s*(?:LPAD\\s*\\([^\\r\\n]*?,\\s*8\\s*,"
                    + "|RIGHT\\s*\\([^\\r\\n]*?,\\s*8\\s*\\)"
                    + "|SUBSTRING\\s*\\([^\\r\\n]*?,\\s*-?8\\s*\\))");

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
        assertThat(service).contains(
                "requestHash(\n                    detection.rowId(),\n                    String.valueOf(detection.userId()))");
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
    void allK2DispositionsUseCanonicalCodesConsumedByH8AndK4() {
        String service = read("src/main/java/ffdd/opsconsole/risk/application/OpsRiskService.java");
        String referral = read("src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java");
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");
        String migration = read("scripts/migrations/20260727_k2_runtime_detection_closure.sql");

        assertThat(service).contains(
                "\"account_flagged\"",
                "\"gift_blocked\"",
                "\"leaderboard_flagged\"",
                "\"cluster_frozen\"");
        assertThat(service).doesNotContain(
                "\"已标记套利\"",
                "\"新人礼已拦截\"",
                "\"已标记刷榜\"",
                "\"已联动 K1 冻结\"");
        assertThat(referral).contains("'gift_blocked'", "'account_flagged'", "'cluster_frozen'");
        assertThat(mapper).contains("'gift_blocked'", "'account_flagged'", "'cluster_frozen'");
        assertThat(migration).contains(
                "WHEN '已标记套利' THEN 'account_flagged'",
                "WHEN '新人礼已拦截' THEN 'gift_blocked'",
                "WHEN '已标记刷榜' THEN 'leaderboard_flagged'",
                "WHEN '已联动 K1 冻结' THEN 'cluster_frozen'");
    }

    @Test
    void runtimeProjectionDoesNotDependOnAnOperatorOpeningTheK2Page() {
        String scheduler = read("src/main/java/ffdd/opsconsole/risk/application/K2DetectionScheduler.java");
        String service = read("src/main/java/ffdd/opsconsole/risk/application/OpsRiskService.java");

        assertThat(scheduler).contains(
                "@Scheduled",
                "nexion.risk.k2-detection-delay-ms",
                "refreshK2AuthoritativeProjection");
        assertThat(service).contains("public void refreshK2AuthoritativeProjection()");
        assertThat(service).contains(
                "refreshE3TradeinArbitrageProjection",
                "refreshTrialCycleArbitrageProjection",
                "refreshWelcomeGiftArbitrageProjection",
                "refreshLeaderboardArbitrageProjection",
                "emitDetectedArbitrageSignals");
    }

    @Test
    void trialLoopsAggregateByCanonicalK1EntityAndProjectEveryAffectedUser() {
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");

        assertThat(mapper).contains(
                "COALESCE(membership.cluster_id, CONCAT('USER:', membership.user_id)) AS entity_key",
                "GROUP BY entity_key",
                "K2-H2-C",
                "JSON_SEARCH(cluster.nodes_json",
                "CAST(SUBSTRING_INDEX(r.cell2, ' ', 1) AS UNSIGNED) AS cycleCount");
    }

    @Test
    void welcomeGiftAndLeaderboardDetectionsHaveRealCanonicalProducers() {
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");
        String migration = read("scripts/migrations/20260727_k2_runtime_detection_closure.sql");

        assertThat(mapper).contains(
                "nx_referral_reward_settlement",
                "upsertWelcomeGiftArbitrageRows",
                "nx_risk_k2_leaderboard_snapshot",
                "captureLeaderboardSnapshot",
                "upsertLeaderboardArbitrageRows",
                "MEDIAN_BASELINE");
        assertThat(migration).contains(
                "CREATE TABLE IF NOT EXISTS nx_risk_k2_leaderboard_snapshot",
                "UNIQUE KEY uk_k2_leaderboard_snapshot");
    }

    @Test
    void k2NeverTruncatesUserNumbersWhenDatabaseIdsExceedEightDigits() {
        String mapper = read("src/main/java/ffdd/opsconsole/risk/mapper/RiskOpsMapper.java");
        int e3Method = mapper.indexOf("int upsertE3TradeinArbitrageRows");
        String k2Section = mapper.substring(
                mapper.lastIndexOf("@Insert", e3Method),
                mapper.indexOf("long countArbitrageRows"));

        assertThat(k2Section).contains(
                "GREATEST(8, CHAR_LENGTH(CAST(a.user_id AS CHAR)))",
                "GREATEST(8, CHAR_LENGTH(CAST(starts.user_id AS CHAR)))",
                "GREATEST(8, CHAR_LENGTH(CAST(u.id AS CHAR)))",
                "GREATEST(8, CHAR_LENGTH(CAST(pairs.user_id AS CHAR)))");
        assertThat(k2Section).doesNotContain("LPAD(a.user_id, 8", "LPAD(u.id, 8", "LPAD(pairs.user_id, 8");
    }

    @Test
    void repositoryNeverTruncatesCanonicalUserNumbers() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("scripts/migrations"))) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".sql"))
                        .forEach(path -> collectTruncatingUserNumberHits(path, hits));
            }
        }

        assertThat(hits)
                .as("PCFULL-194: canonical U{databaseId} generation/resolution must preserve every database-id digit.%n%s",
                        String.join(System.lineSeparator(), hits))
                .isEmpty();
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

    private static void collectTruncatingUserNumberHits(Path path, List<String> hits) {
        String[] lines = read(path.toString()).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (TRUNCATING_USER_NUMBER.matcher(lines[index]).find()) {
                hits.add(path + ":" + (index + 1) + " " + lines[index].trim());
            }
        }
    }
}
