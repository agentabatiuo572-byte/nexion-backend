package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamCommissionMapperVRankContractTest {
    @Test
    void populationQueriesStartWithNonDeletedUsersAndUseCanonicalSelfLoopRankWithV0Fallback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));
        String rankRows = source.substring(source.lastIndexOf("@Select", source.indexOf("List<Map<String, Object>> vRankRows()")),
                source.indexOf("List<Map<String, Object>> vRankRows()"));
        String leadershipRanks = source.substring(source.lastIndexOf("@Select", source.indexOf("List<Map<String, Object>> leadershipRanks()")),
                source.indexOf("List<Map<String, Object>> leadershipRanks()"));

        assertPopulationContract(rankRows);
        assertPopulationContract(leadershipRanks);
    }

    private void assertPopulationContract(String query) {
        assertThat(query)
                .contains("COUNT(p.user_id) AS pop")
                .contains("FROM nx_user u")
                .contains("WHERE u.is_deleted = 0")
                .contains("MIN(self_row.id)")
                .contains("self_row.user_id = u.id")
                .contains("self_row.member_user_id = u.id")
                .contains("self_row.is_deleted = 0")
                .contains("m.is_deleted = 0")
                .contains("m.v_rank REGEXP '^[[:space:]]*$'")
                .contains("ELSE TRIM(UPPER(m.v_rank))")
                .contains("p.rank_code = UPPER(c.rank_code)");
    }

    @Test
    void earliestActiveSelfContractDoesNotPromoteAUserForLaterDuplicateRows() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));
        int method = source.indexOf("String currentMemberVRank");
        int query = source.lastIndexOf("@Select", method);
        String contract = source.substring(query, method);

        assertThat(contract).contains("ORDER BY id ASC").doesNotContain("MAX(v_rank)");
        assertThat(canonicalRank(List.of(new SelfRow(10, "V2", false), new SelfRow(11, "V2", false))))
                .isEqualTo("V2");
        assertThat(canonicalRank(List.of(new SelfRow(10, "V0", false), new SelfRow(11, "V9", false))))
                .isEqualTo("V0");
        assertThat(canonicalRank(List.of(new SelfRow(10, "V0", true), new SelfRow(11, "V4", false))))
                .isEqualTo("V4");
        assertThat(canonicalRank(List.of(new SelfRow(10, "\u2003", false)))).isEqualTo("V0");
    }

    private static String canonicalRank(List<SelfRow> rows) {
        return rows.stream()
                .filter(row -> !row.deleted())
                .min(Comparator.comparingLong(SelfRow::id))
                .map(SelfRow::rank)
                .filter(rank -> rank != null && !rank.isBlank())
                .map(String::trim)
                .orElse("V0");
    }

    private record SelfRow(long id, String rank, boolean deleted) {
    }

    @Test
    void optInPopulationFixtureRejectsCatalogAndNonLoopbackBeforeConnecting() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                TeamCommissionMapperVRankMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/nexion"));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                TeamCommissionMapperVRankMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://192.168.8.10:3306/"));
        TeamCommissionMapperVRankMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://[::1]:3306/?useSSL=false");
    }
    @Test
    void rankEvaluationSerializesOnTheCanonicalSelfLoopRow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));
        int method = source.indexOf("String currentMemberVRank");
        int query = source.lastIndexOf("@Select", method);
        String contract = source.substring(query, method);

        assertThat(contract)
                .contains("member_user_id = #{userId}")
                .contains("ORDER BY id ASC")
                .contains("FOR UPDATE");
    }
}
