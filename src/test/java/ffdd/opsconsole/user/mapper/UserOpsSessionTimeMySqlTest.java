package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class UserOpsSessionTimeMySqlTest {
    private static final String PREFIX = "nx_c5_session_time_it_";

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_C5_SESSION_TIME_IT", matches = "true")
    void c5ReadsAndRevokesBusinessTimeWithUtcDatabaseConnection() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        if (!"127.0.0.1:23308".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String base = "jdbc:mysql://" + endpoint + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(base + options, "root", password));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(23308);
        admin.execute("CREATE DATABASE " + schema);
        SingleConnectionDataSource source = null;
        try {
            source = new SingleConnectionDataSource(
                    new DriverManagerDataSource(base + schema + options, "root", password).getConnection(), true);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
            jdbc.execute("SET time_zone = '+00:00'");
            jdbc.execute("""
                    CREATE TABLE nx_user_session (
                      id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                      refresh_token_id VARCHAR(96) NOT NULL, device_name VARCHAR(96), client_ip VARCHAR(64),
                      created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
                      last_active_at DATETIME, expires_at DATETIME NOT NULL,
                      revoked_at DATETIME, is_deleted TINYINT NOT NULL
                    ) ENGINE=InnoDB
                    """);
            jdbc.update("""
                    INSERT INTO nx_user_session VALUES
                    (1,7,'single','App','127.0.0.1',
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),NULL,0),
                    (2,7,'expired','App','127.0.0.1',
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 470 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 470 MINUTE),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 470 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),NULL,0),
                    (3,7,'active','App','127.0.0.1',
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),
                     DATE_ADD(UTC_TIMESTAMP(),INTERVAL 479 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),NULL,0),
                    (4,7,'idle','App','127.0.0.1',
                     DATE_SUB(UTC_TIMESTAMP(),INTERVAL 31 DAY),DATE_SUB(UTC_TIMESTAMP(),INTERVAL 31 DAY),
                     DATE_SUB(UTC_TIMESTAMP(),INTERVAL 31 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 9 HOUR),NULL,0)
                    """);
            Configuration config = new Configuration(new Environment("c5-time", new JdbcTransactionFactory(), source));
            config.addMapper(UserOpsMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                UserOpsMapper mapper = session.getMapper(UserOpsMapper.class);
                assertThat(mapper.countActiveSessions()).isEqualTo(3);
                assertThat(mapper.findSession("expired").status()).isEqualTo("EXPIRED");
                assertThat(mapper.revokeSession("single")).isEqualTo(1);
                assertThat(mapper.revokeSession("expired")).isZero();
                assertThat(mapper.revokeActiveUserSessions(7L, 30)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM nx_user_session WHERE id=4", Boolean.class))
                        .isTrue();
                assertThat(mapper.revokeUserSessions(7L)).isEqualTo(2);
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM nx_user_session
                         WHERE revoked_at >= created_at
                           AND ABS(TIMESTAMPDIFF(SECOND, revoked_at, DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR))) < 5
                        """, Integer.class)).isEqualTo(4);
            }
        } finally {
            try {
                if (source != null) source.destroy();
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }
}
