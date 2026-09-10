package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Actual H3 mapper reads against owned fixtures, never existing mission or user data. */
class GrowthQuestEventMapperMissionRowsReadOnlyMySqlTest {
    private static final String PREFIX = "nx_h3_mission_rows_it_";

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
    @EnabledIfEnvironmentVariable(named = "NEXION_H3_MISSION_ROWS_IT", matches = "true")
    void canonicalBindingsAreCorrelatedPerMissionAndIgnoreInactiveOrDeletedBindings() throws Exception {
        inSchema((jdbc, configuration) -> {
            jdbc.execute("CREATE TABLE nx_mission(id BIGINT PRIMARY KEY,mission_code VARCHAR(80),mission_name VARCHAR(100),"
                    + "mission_type VARCHAR(24),reward_points DECIMAL(20,6),status INT,mission_category VARCHAR(24),"
                    + "action_route VARCHAR(120),is_deleted TINYINT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE nx_growth_quest_event_binding(binding_code VARCHAR(80) PRIMARY KEY,"
                    + "quest_code VARCHAR(80),event_type VARCHAR(80),status INT,is_deleted TINYINT) ENGINE=InnoDB");
            jdbc.update("INSERT INTO nx_mission VALUES"
                    + "(1,'D1-EARN','Earn','DAY_ONE',12.5,1,'EXPLORE','/pages/earn/earn',0),"
                    + "(2,'D1-UNBOUND','Unbound','DAY_ONE',0,1,'WALLET','/pages/me/wallet',0),"
                    + "(3,'D1-PAUSED','Paused','DAY_ONE',3,0,'EXPLORE','/pages/store/store',0),"
                    + "(4,'D1-ARCHIVED','Archived','DAY_ONE',4,2,'SOCIAL','/pages/me/me',0),"
                    + "(5,'D1-DELETED','Deleted','DAY_ONE',5,1,'EXPLORE','/pages/earn/earn',1),"
                    + "(6,'WEEKLY','Weekly','WEEKLY_T1',6,1,'EXPLORE','/pages/earn/earn',0)");
            jdbc.update("INSERT INTO nx_growth_quest_event_binding VALUES"
                    + "('B','D1-EARN','SECOND_EVENT',1,0),('A','D1-EARN','FIRST_EVENT',1,0),"
                    + "('C','D1-UNBOUND','PAUSED_EVENT',0,0),('D','D1-UNBOUND','DELETED_EVENT',1,1),"
                    + "('E','D1-PAUSED','PAUSED_MISSION_EVENT',1,0),('F','WEEKLY','WEEKLY_EVENT',1,0)");
            configuration.addMapper(GrowthQuestEventMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(GrowthQuestEventMapper.class);
                var rows = mapper.missionRows("DAY_ONE");
                assertThat(rows).extracting(row -> row.get("taskCode"))
                        .containsExactly("D1-EARN", "D1-UNBOUND", "D1-PAUSED", "D1-ARCHIVED");
                assertThat(rows.get(0)).containsEntry("completionType", "event")
                        .containsEntry("completionEvent", "FIRST_EVENT, SECOND_EVENT")
                        .containsEntry("category", "explore").containsEntry("href", "/pages/earn/earn");
                assertThat(rows.get(1)).containsEntry("completionType", "unbound").containsEntry("completionEvent", "");
                assertThat(rows.get(2)).containsEntry("status", "paused").containsEntry("completionType", "event")
                        .containsEntry("completionEvent", "PAUSED_MISSION_EVENT");
                assertThat(rows.get(3)).containsEntry("status", "archived").containsEntry("completionType", "unbound");
                assertThat(mapper.missionRows("WEEKLY_T1")).singleElement()
                        .satisfies(row -> assertThat(row).containsEntry("completionEvent", "WEEKLY_EVENT"));
                assertThat(mapper.missionRows("DAY_ONE' OR 1=1 --")).isEmpty();
            }
        });
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
            Configuration configuration = new Configuration(new Environment("h3-fixture", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            test.run(jdbc, configuration);
        } finally {
            try {
                if (dataSource != null) dataSource.destroy();
            } finally {
                admin.execute("DROP DATABASE " + schema);
            }
        }
    }

    @FunctionalInterface private interface SchemaTest { void run(JdbcTemplate jdbc, Configuration configuration) throws Exception; }
}
