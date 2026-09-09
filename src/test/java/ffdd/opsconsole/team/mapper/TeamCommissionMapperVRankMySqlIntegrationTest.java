package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Strict opt-in execution of TeamCommissionMapper's two F1 population annotation queries. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamCommissionMapperVRankMySqlIntegrationTest {
    private static final Pattern LOOPBACK_SERVER_URL = Pattern.compile(
            "^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNED_SCHEMA = Pattern.compile("^nx_f1_vrank_population_test_[0-9a-f]{32}$");
    private Connection admin;
    private String schema;
    private boolean created;
    private JdbcTemplate jdbc;
    private TeamCommissionMapper mapper;

    @BeforeAll
    void openOwnedFixture() throws Exception {
        String serverUrl = System.getenv().getOrDefault("NEXION_TEST_DB_SERVER_URL",
                "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        requireServerUrlWithoutCatalog(serverUrl);
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(serverUrl, username, password);
        schema = "nx_f1_vrank_population_test_" + UUID.randomUUID().toString().replace("-", "");
        requireOwnedSchema(schema);
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        DataSource source = ownedDataSource(fixtureUrl(serverUrl, schema), username, password, schema);
        jdbc = new JdbcTemplate(source);
        createTables();
        Configuration configuration = new Configuration(new Environment("f1-vrank-population", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TeamCommissionMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration)).getMapper(TeamCommissionMapper.class);
    }

    @AfterAll
    void closeOwnedFixture() throws Exception {
        if (admin == null) return;
        try {
            if (created && isOwnedSchema(schema)) {
                try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE `" + schema + "`"); }
            }
        } finally { admin.close(); }
    }

    @Test
    void annotatedPopulationQueriesCountEveryNonDeletedUserExactlyOnceWithEarliestActiveSelfRank() {
        seed();
        Map<String, Long> all = mapper.vRankRows().stream().collect(java.util.stream.Collectors.toMap(
                row -> (String) row.get("v"), row -> ((Number) row.get("pop")).longValue()));
        Map<Integer, Long> leadership = mapper.leadershipRanks().stream().collect(java.util.stream.Collectors.toMap(
                row -> ((Number) row.get("v")).intValue(), row -> ((Number) row.get("pop")).longValue()));

        assertThat(all).containsEntry("V0", 9L).containsEntry("V2", 1L).containsEntry("V3", 0L).containsEntry("V4", 2L);
        assertThat(all.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(12L);
        assertThat(leadership).containsExactlyInAnyOrderEntriesOf(Map.of(2, 1L, 3, 0L, 4, 2L));
        assertThat(mapper.currentMemberVRank(3L)).isEqualTo("V0");
        assertThat(mapper.currentMemberVRank(8L)).isEqualTo("V4");
    }

    static void requireServerUrlWithoutCatalog(String url) {
        if (url == null || !LOOPBACK_SERVER_URL.matcher(url).matches()) {
            throw new IllegalArgumentException("NEXION_TEST_DB_SERVER_URL must be loopback JDBC MySQL URL with no catalog");
        }
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY, is_deleted TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE nx_team_member (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, member_user_id BIGINT NOT NULL, v_rank VARCHAR(16), is_deleted TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE nx_v_rank_config (id BIGINT PRIMARY KEY, rank_code VARCHAR(16), title_cn VARCHAR(64), self_buy_usd DECIMAL(18,2), direct_refs INT, team_volume_usd DECIMAL(18,2), required_downline_count INT, required_downline_rank VARCHAR(16), unilevel_depth INT, peer_bonus_rate DECIMAL(18,6), leadership_votes INT, status TINYINT, physical_reward VARCHAR(128), is_deleted TINYINT, sort_order INT)");
    }

    private void seed() {
        for (long id = 1; id <= 13; id++) jdbc.update("INSERT INTO nx_user(id,is_deleted) VALUES (?,?)", id, id == 4 ? 1 : 0);
        config(1, "V0", 0); config(2, "V2", 1); config(3, "V3", 1); config(4, "V4", 2);
        member(10, 1, 1, "V2", 0);
        member(30, 3, 3, "V0", 0); member(31, 3, 3, "V4", 0);
        member(40, 4, 4, "V4", 0);
        member(50, 5, 5, null, 0); member(60, 6, 6, "   ", 0);
        member(70, 7, 99, "V4", 0);
        member(79, 8, 8, "V0", 1); member(80, 8, 8, "V4", 0);
        member(90, 9, 9, "V4", 0); member(91, 9, 9, "V4", 0);
        member(100, 10, 10, "V2", 1);
        member(110, 11, 11, "\t", 0); member(120, 12, 12, "\n", 0); member(130, 13, 13, "\u2003", 0);
    }

    private void config(long id, String rank, int votes) {
        jdbc.update("INSERT INTO nx_v_rank_config(id,rank_code,title_cn,self_buy_usd,direct_refs,team_volume_usd,required_downline_count,required_downline_rank,unilevel_depth,peer_bonus_rate,leadership_votes,status,physical_reward,is_deleted,sort_order) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, rank, rank, 0, 0, 0, 0, "", 0, 0, votes, 1, "", 0, id);
    }

    private void member(long id, long user, long member, String rank, int deleted) {
        jdbc.update("INSERT INTO nx_team_member(id,user_id,member_user_id,v_rank,is_deleted) VALUES (?,?,?,?,?)", id, user, member, rank, deleted);
    }

    private static DataSource ownedDataSource(String url, String username, String password, String schema) {
        return new DelegatingDataSource(new DriverManagerDataSource(url, username, password)) {
            @Override public Connection getConnection() throws SQLException { return assertOwned(super.getConnection(), schema); }
            @Override public Connection getConnection(String user, String pass) throws SQLException { return assertOwned(super.getConnection(user, pass), schema); }
        };
    }

    private static Connection assertOwned(Connection connection, String schema) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
            if (!result.next() || !schema.equals(result.getString(1))) throw new SQLException("F1 fixture connection outside owned schema");
            return connection;
        } catch (SQLException ex) { try { connection.close(); } catch (SQLException ignored) { } throw ex; }
    }

    private static String fixtureUrl(String serverUrl, String schema) {
        int query = serverUrl.indexOf('?');
        return serverUrl.substring(0, query < 0 ? serverUrl.length() : query) + schema + (query < 0 ? "" : serverUrl.substring(query));
    }

    private static void requireOwnedSchema(String value) { if (!isOwnedSchema(value)) throw new IllegalArgumentException("F1 fixture schema is not owned"); }
    private static boolean isOwnedSchema(String value) { return value != null && OWNED_SCHEMA.matcher(value).matches(); }
}