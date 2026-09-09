package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LeaderboardEligibilityMapperContractTest {
    @Test
    void periodicSettlementFiltersCanonicalUserEligibilityBeforeGroupingAndTopLimit() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));
        int signature = source.indexOf("List<Map<String, Object>> leaderboardCandidatesByPeriod(");
        String query = source.substring(source.lastIndexOf("@Select", signature), signature);

        assertThat(query)
                .contains("JOIN nx_user u ON u.id = e.user_id")
                .contains("u.is_deleted = 0")
                .contains("u.status = 'ACTIVE'")
                .contains("u.sandbox = 0")
                .contains("UPPER(e.status) = 'UNLOCKED'")
                .contains("LOWER(e.commission_type) IN")
                .contains("HAVING SUM(e.amount_usdt) &gt;= #{minVolumeUsd}")
                .contains("ORDER BY SUM(e.amount_usdt) DESC, e.user_id ASC")
                .contains("LIMIT #{limit}");
        assertThat(query.indexOf("u.sandbox = 0")).isLessThan(query.indexOf("GROUP BY e.user_id"));
        assertThat(query.indexOf("u.sandbox = 0")).isLessThan(query.indexOf("LIMIT #{limit}"));
    }

    @Test
    void fixtureRefusesCatalogOrNonLoopbackBeforeConnecting() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                LeaderboardEligibilityMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/nexion"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                LeaderboardEligibilityMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://192.168.8.10:3306/"));
        LeaderboardEligibilityMySqlIntegrationTest.requireServerUrlWithoutCatalog("jdbc:mysql://[::1]:3306/?useSSL=false");
    }
}
