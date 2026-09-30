package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises the actual H2 paged statement using only an owned isolated schema. */
class GrowthQuestEventMapperTrialSessionsPageMySqlTest {
    private static final String PREFIX = "nx_h2_trial_page_it_";

    @Test
    void pagedStatementProjectsTheExpiryBoundaryInsteadOfTheUnusedLegacyStatement() throws Exception {
        String sql = String.join(" ", GrowthQuestEventMapper.class
                .getMethod("trialSessionsPage", long.class, int.class).getAnnotation(Select.class).value());
        assertThat(sql).contains("UPPER(status) IN ('CLAIMED','ACTIVE') AND expires_at <= NOW() THEN 'grace'")
                .contains("ELSE LOWER(status)").contains("LIMIT #{offset}, #{limit}");
    }

    @Test
    void rejectsBusinessEndpointsAndUnownedSchemasBeforeConnecting() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).contains(":13306/" + schema);
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/other", "127.0.0.1:13306?x=y"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{null, "nexion", "mysql", schema + "`", PREFIX + "A".repeat(32)}) {
            assertThatThrownBy(() -> url("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_H2_TRIAL_PAGE_IT", matches = "true")
    void pagedStatesMatchStatsWithoutChangingStoredStatusIncludingExpiryAndNullBoundaries() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String fixtureUrl = url(endpoint, schema);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url(endpoint, ""), "root", ""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(admin.queryForObject("SELECT DATABASE()", String.class)).isNull();
        admin.execute("CREATE DATABASE " + schema);
        SingleConnectionDataSource dataSource = null;
        try {
            dataSource = new SingleConnectionDataSource(new DriverManagerDataSource(fixtureUrl, "root", "").getConnection(), true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
            // Freeze NOW() on this fixture connection so equality is deterministic.
            jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2026-09-30 00:00:00')");
            jdbc.execute("CREATE TABLE nx_trial_claim(id BIGINT PRIMARY KEY,claim_no VARCHAR(80),status VARCHAR(24),"
                    + "daily_usdt DECIMAL(20,6),daily_nex DECIMAL(20,6),duration_days INT,shadow_accrued_usdt DECIMAL(20,6),"
                    + "shadow_accrued_nex DECIMAL(20,6),claimed_at DATETIME,expires_at DATETIME,cooldown_until DATETIME,"
                    + "is_deleted TINYINT) ENGINE=InnoDB");
            Map<String, String> expected = new LinkedHashMap<>();
            seed(jdbc, expected, 1, "ACTIVE", 1, "active");
            seed(jdbc, expected, 2, "CLAIMED", 1, "claimed");
            seed(jdbc, expected, 3, "ACTIVE", 0, "grace");
            seed(jdbc, expected, 4, "CLAIMED", 0, "grace");
            seed(jdbc, expected, 5, "ACTIVE", -1, "grace");
            seed(jdbc, expected, 6, "CLAIMED", -1, "grace");
            seed(jdbc, expected, 7, "GRACE", 1, "grace");
            seed(jdbc, expected, 8, "EXTENDED", -1, "extended");
            seed(jdbc, expected, 9, "CANCELLED", -1, "cancelled");
            seed(jdbc, expected, 10, "FAILED", -1, "failed");
            seed(jdbc, expected, 11, "REDEEMED", -1, "redeemed");
            seed(jdbc, expected, 12, "ACTIVE", null, "active");
            seed(jdbc, expected, 13, "CLAIMED", null, "claimed");
            seed(jdbc, expected, 14, "active", -1, "grace");
            jdbc.execute("INSERT INTO nx_trial_claim VALUES(15,'DELETED','ACTIVE',40,5,3,120,15,NOW(),NOW(),NULL,1)");
            var persistedBefore = jdbc.queryForList("SELECT * FROM nx_trial_claim ORDER BY id");

            Configuration configuration = new Configuration(new Environment("h2-trial-page-fixture",
                    new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(GrowthQuestEventMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(GrowthQuestEventMapper.class);
                var rows = mapper.trialSessionsPage(0, 100);
                assertThat(rows).hasSize(14).allSatisfy(row ->
                        assertThat(row.get("state")).isEqualTo(expected.get(row.get("sid"))));
                assertThat(mapper.countTrialSessions()).isEqualTo(14);
                assertThat(mapper.trialSessionsPage(0, 10)).containsExactlyElementsOf(rows.subList(0, 10));
                assertThat(mapper.trialSessionsPage(10, 10)).containsExactlyElementsOf(rows.subList(10, 14));
                assertThat(mapper.trialSessionsPage(14, 10)).isEmpty();
                assertThat(rows).extracting(row -> row.get("sid"))
                        .containsExactly("trial-CASE-14", "trial-CASE-13", "trial-CASE-12", "trial-CASE-11",
                                "trial-CASE-10", "trial-CASE-9", "trial-CASE-8", "trial-CASE-7", "trial-CASE-6",
                                "trial-CASE-5", "trial-CASE-4", "trial-CASE-3", "trial-CASE-2", "trial-CASE-1");
                var stats = mapper.trialStats();
                assertThat(((Number) stats.get("activeSessions")).intValue()).isEqualTo(11);
                assertThat(((Number) stats.get("inTrial")).intValue()).isEqualTo(2);
                assertThat(((Number) stats.get("inGrace")).intValue()).isEqualTo(6);
                assertThat(((Number) stats.get("inExtended")).intValue()).isEqualTo(1);
                assertThat(rows.stream().filter(row -> "grace".equals(row.get("state"))).count()).isEqualTo(6);
                // Null expiry remains active/claimed; existing stats include it only in activeSessions.
                assertThat(rows.stream().filter(row -> row.get("expiresAt") != null
                        && ("active".equals(row.get("state")) || "claimed".equals(row.get("state")))).count()).isEqualTo(2);
                assertThat(jdbc.queryForList("SELECT * FROM nx_trial_claim ORDER BY id")).isEqualTo(persistedBefore);
            }
        } finally {
            try {
                if (dataSource != null) dataSource.destroy();
            } finally {
                admin.execute("DROP DATABASE " + schema);
            }
        }
    }

    private static void seed(JdbcTemplate jdbc, Map<String, String> expected, int id, String status,
                             Integer expiryOffset, String state) {
        jdbc.update("INSERT INTO nx_trial_claim VALUES(?,?,?,40,5,3,120,15,DATE_SUB(NOW(),INTERVAL 3 DAY),"
                + "DATE_ADD(NOW(),INTERVAL ? SECOND),NULL,0)", id, "CASE-" + id, status, expiryOffset);
        expected.put("trial-CASE-" + id, state);
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
