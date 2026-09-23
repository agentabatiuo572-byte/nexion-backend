package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AuthSessionMapperMySqlIntegrationTest {
    private static final String PREFIX = "nx_auth_grace_it_";

    @Test
    void onlyAllowsOwnedSchemasOnTheIsolatedPort() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13307", schema)).contains(":13307/" + schema);
        for (String endpoint : new String[]{null, "localhost:13307", "127.0.0.1:3306", "127.0.0.1:13307/other"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> url("127.0.0.1:13307", "nexion"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_233_SESSION_MAPPER_IT", matches = "true")
    void oldBearerGraceRequiresRecentRotationAndLiveSameChainSuccessor() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String fixtureUrl = url(endpoint, schema);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url(endpoint, ""), "root", password));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13307);
        assertThat(admin.queryForObject("SELECT DATABASE()", String.class)).isNull();
        admin.execute("CREATE DATABASE " + schema);
        SingleConnectionDataSource dataSource = null;
        try {
            dataSource = new SingleConnectionDataSource(
                    new DriverManagerDataSource(fixtureUrl, "root", password).getConnection(), true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13307);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
            jdbc.execute("""
                    CREATE TABLE nx_user_session (
                      id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                      refresh_token_id VARCHAR(96) NOT NULL, session_chain_id VARCHAR(96) NOT NULL,
                      rotated_to_id VARCHAR(96), rotation_redeemed_at DATETIME,
                      revoked_at DATETIME, last_active_at DATETIME,
                      expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL,
                      updated_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL
                    ) ENGINE=InnoDB
                    """);
            jdbc.update("""
                    INSERT INTO nx_user_session VALUES
                    (1,7,'old','chain-a','new',DATE_SUB(NOW(),INTERVAL 9 SECOND),NOW(),
                     NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW(),0),
                    (2,7,'new','chain-a',NULL,NULL,NULL,
                     DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW(),0),
                    (3,7,'other','chain-b',NULL,NULL,NULL,
                     DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW(),0),
                    (4,8,'other-user','chain-c',NULL,NULL,NULL,
                     DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW(),0)
                    """);
            Configuration configuration = new Configuration(
                    new Environment("auth-grace-fixture", new JdbcTransactionFactory(), dataSource));
            configuration.addMapper(AuthSessionMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                AuthSessionMapper mapper = session.getMapper(AuthSessionMapper.class);
                jdbc.update("UPDATE nx_user_session SET revoked_at=NOW() WHERE id=2");
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 7L, 30)).isZero();
                jdbc.update("UPDATE nx_user_session SET revoked_at=NULL WHERE id=2");
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 7L, 30)).isEqualTo(1);
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 8L, 30)).isZero();

                jdbc.update("UPDATE nx_user_session SET rotation_redeemed_at=DATE_SUB(NOW(),INTERVAL 11 SECOND) WHERE id=1");
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 7L, 30)).isZero();

                jdbc.update("UPDATE nx_user_session SET rotation_redeemed_at=DATE_SUB(NOW(),INTERVAL 9 SECOND) WHERE id=1");
                assertThat(mapper.revokeRefreshChain("chain-a")).isEqualTo(2);
                assertThat(mapper.touchRecentlyRotatedUserSession("old", 7L, 30)).isZero();
                assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM nx_user_session WHERE id=2", Boolean.class))
                        .isFalse();
                assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM nx_user_session WHERE id=3", Boolean.class))
                        .isTrue();
            }
        } finally {
            try {
                if (dataSource != null) dataSource.destroy();
            } finally {
                admin.execute("DROP DATABASE " + schema);
            }
        }
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13307".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
