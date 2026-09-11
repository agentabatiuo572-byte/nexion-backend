package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Actual event join/claim SQL in owned schemas, with no wallet or reward dispatcher. */
class EventQuestClaimWindowMySqlTest {
    private static final String PREFIX = "nx_event_claim_it_";

    @Test
    void rejectsBusinessEndpointsAndUnownedSchemasBeforeConnecting() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).startsWith("jdbc:mysql://127.0.0.1:13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/nexion", "127.0.0.1:13306?x=y"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String schemaName : new String[]{null, "nexion", "mysql", schema + "`", schema.toUpperCase(), schema + "/x"}) {
            assertThatThrownBy(() -> url("127.0.0.1:13306", schemaName)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_EVENT_CLAIM_IT", matches = "true")
    void claimRechecksExclusiveUtcDeadlineAfterAnEarlierSuccessfulLock() throws Exception {
        inSchema((jdbc, mapper, session) -> {
            clock(jdbc, session, "2026-09-06 16:59:59"); // UTC 09:59:59, one second before the deadline.
            assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNotNull();
            clock(jdbc, session, "2026-09-06 17:00:00");
            assertThat(mapper.claimEvent(7L, "event-1")).isZero();
            assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNull();
            assertThat(mapper.lockOpenEvent("event-1")).isNull();
            assertUnclaimed(jdbc, 7L);

            clock(jdbc, session, "2026-09-06 15:59:59");
            assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNull();
            assertThat(mapper.claimEvent(7L, "event-1")).isZero();
            clock(jdbc, session, "2026-09-06 16:00:00"); // Exact UTC start is inclusive.
            assertThat(mapper.lockOpenEvent("event-1")).isNotNull();
            assertThat(mapper.lockClaimableEvent(7L, "event-1").rewardAmount()).isEqualByComparingTo("10.123456");
            assertThat(mapper.claimEvent(7L, "event-1")).isEqualTo(1);
            assertThat(mapper.claimEvent(7L, "event-1")).isZero();
            assertThat(jdbc.queryForObject("SELECT claim_status FROM nx_user_event_quest WHERE user_id=7", String.class))
                    .isEqualTo("CLAIMED");
            assertUnclaimed(jdbc, 8L);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_EVENT_CLAIM_IT", matches = "true")
    void claimRejectsForeignMissingDeletedInactiveAndIncompleteRows() throws Exception {
        inSchema((jdbc, mapper, session) -> {
            clock(jdbc, session, "2026-09-06 16:30:00");
            assertThat(mapper.lockClaimableEvent(99L, "event-1")).isNull();
            assertThat(mapper.claimEvent(99L, "event-1")).isZero();
            assertThat(mapper.claimEvent(7L, "event-1' OR 1=1 --")).isZero();
            assertThat(mapper.lockClaimableEvent(7L, "event-1' OR 1=1 --")).isNull();
            for (String change : new String[]{"status=0", "status=2", "is_deleted=1"}) {
                jdbc.update("UPDATE nx_event_quest SET status=1,is_deleted=0");
                jdbc.update("UPDATE nx_event_quest SET " + change);
                session.clearCache();
                assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNull();
                assertThat(mapper.claimEvent(7L, "event-1")).isZero();
                assertUnclaimed(jdbc, 7L);
            }
            jdbc.update("UPDATE nx_event_quest SET status=1,is_deleted=0");
            jdbc.update("UPDATE nx_user_event_quest SET is_deleted=1 WHERE user_id=7");
            session.clearCache();
            assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNull();
            assertThat(mapper.claimEvent(7L, "event-1")).isZero();
            jdbc.update("UPDATE nx_user_event_quest SET is_deleted=0,progress_value=0,claim_status='JOINED' WHERE user_id=7");
            session.clearCache();
            assertThat(mapper.lockClaimableEvent(7L, "event-1")).isNull();
            assertThat(mapper.claimEvent(7L, "event-1")).isZero();
            assertUnclaimed(jdbc, 7L);
            assertUnclaimed(jdbc, 8L);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_EVENT_CLAIM_IT", matches = "true")
    void joiningSnapshotsRewardAndOpenEndedEventsRemainClaimableAfterPcRewardChanges() throws Exception {
        inSchema((jdbc, mapper, session) -> {
            jdbc.update("UPDATE nx_event_quest SET starts_at=NULL,ends_at=NULL");
            clock(jdbc, session, "2027-01-01 07:00:00");
            var original = mapper.lockOpenEvent("event-1");
            assertThat(original.rewardAmount()).isEqualByComparingTo("30.123456");
            assertThat(mapper.joinEvent(9L, original)).isEqualTo(1);
            assertThat(mapper.joinEvent(9L, original)).isZero();
            jdbc.update("UPDATE nx_event_quest SET reward_type='USDT',reward_amount=999");
            jdbc.update("UPDATE nx_user_event_quest SET progress_value=1 WHERE user_id=9");
            session.clearCache();
            var earned = mapper.lockClaimableEvent(9L, "event-1");
            assertThat(earned.rewardType()).isEqualTo("NEX");
            assertThat(earned.rewardAmount()).isEqualByComparingTo("30.123456");
            assertThat(earned.badgeCode()).isEqualTo("BADGE-A");
            assertThat(mapper.claimEvent(9L, "event-1")).isEqualTo(1);
            assertThat(mapper.claimEvent(9L, "event-1")).isZero();
            assertUnclaimed(jdbc, 7L);
            assertUnclaimed(jdbc, 8L);
        });
    }

    private static void clock(JdbcTemplate jdbc, SqlSession session, String localTime) {
        jdbc.update("SET timestamp=UNIX_TIMESTAMP(?)", localTime);
        session.clearCache(); // Fixture time/rows changed outside MyBatis; force the next real SELECT.
    }

    private static void assertUnclaimed(JdbcTemplate jdbc, long userId) {
        assertThat(jdbc.queryForObject("SELECT claim_status FROM nx_user_event_quest WHERE user_id=?", String.class, userId))
                .isNotEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT claimed_at FROM nx_user_event_quest WHERE user_id=?", String.class, userId)).isNull();
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static void inSchema(SchemaTest test) throws Exception {
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
            jdbc.execute("CREATE TABLE nx_event_quest(id BIGINT PRIMARY KEY,quest_code VARCHAR(64) UNIQUE,target_value INT,"
                    + "status INT,is_deleted INT,badge_achievement_code VARCHAR(64),starts_at DATETIME,ends_at DATETIME,"
                    + "reward_type VARCHAR(16),reward_amount DECIMAL(20,6)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE nx_user_event_quest(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,quest_id BIGINT,"
                    + "quest_code VARCHAR(64),progress_value INT,claim_status VARCHAR(32),reward_type VARCHAR(16),"
                    + "reward_amount DECIMAL(20,6),is_deleted INT,claimed_at DATETIME,created_at DATETIME,updated_at DATETIME,"
                    + "UNIQUE KEY uk_user_event(user_id,quest_code)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO nx_event_quest VALUES(1,'event-1',1,1,0,'BADGE-A',"
                    + "'2026-09-06 09:00:00','2026-09-06 10:00:00','NEX',30.123456)");
            jdbc.update("INSERT INTO nx_user_event_quest(user_id,quest_id,quest_code,progress_value,claim_status,reward_type,reward_amount,is_deleted)"
                    + " VALUES(7,1,'event-1',1,'CLAIMABLE','NEX',10.123456,0),(8,1,'event-1',1,'CLAIMABLE','NEX',10.123456,0)");
            jdbc.execute("SET time_zone='+07:00'");
            Configuration config = new Configuration(new Environment("event-claim-fixture", new JdbcTransactionFactory(), dataSource));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(AppGrowthEngagementMapper.class);
            try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(false)) {
                test.run(jdbc, session.getMapper(AppGrowthEngagementMapper.class), session);
            }
        } finally {
            try {
                if (dataSource != null) dataSource.destroy();
            } finally {
                admin.execute("DROP DATABASE " + schema);
            }
        }
    }

    @FunctionalInterface private interface SchemaTest {
        void run(JdbcTemplate jdbc, AppGrowthEngagementMapper mapper, SqlSession session) throws Exception;
    }
}
