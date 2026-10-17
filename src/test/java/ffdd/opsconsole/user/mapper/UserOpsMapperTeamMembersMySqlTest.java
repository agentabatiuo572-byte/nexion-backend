package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Real mapper regression in one explicitly disposable schema, never the business database. */
class UserOpsMapperTeamMembersMySqlTest {
    private static final String PREFIX = "nexion_c1_team_it_";

    @Test
    void rejectsBusinessEndpointsAndUnownedSchemaNames() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).contains(":13306/" + schema);
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306"})
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[]{null, "mysql", "nexion", schema + "`"})
            assertThatThrownBy(() -> url("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_C1_TEAM_IT", matches = "true")
    void sameUserNoUsesLiveNicknameEvenWhenLegacyMemberIdPointsElsewhere() throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url(endpoint, ""), "root", ""));
        admin.execute("CREATE DATABASE " + schema);
        System.out.println("BRAND_READ_SCHEMA_CREATED " + schema);
        SingleConnectionDataSource dataSource = null;
        try {
            dataSource = new SingleConnectionDataSource(
                    new DriverManagerDataSource(url(endpoint, schema), "root", "").getConnection(), true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),is_deleted TINYINT NOT NULL,phone VARCHAR(32))");
            jdbc.execute("CREATE TABLE nx_team_member(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,member_user_id BIGINT NOT NULL,member_no VARCHAR(64) NOT NULL,nickname VARCHAR(100),v_rank VARCHAR(16),level INT,volume DECIMAL(20,2),created_at DATETIME,is_deleted TINYINT NOT NULL)");
            jdbc.update("INSERT INTO nx_user(id,nickname,is_deleted) VALUES(60723153012,'Turbo Pulse 36',0),(9831,'Wrong live user',0)");
            jdbc.update("INSERT INTO nx_team_member VALUES(1,7,9831,'U60723153012','NexGrid 9831','V1',1,0,NOW(),0)");

            record NameCase(String live, String phone, String fallback, int deleted, String expected) { }
            var names = java.util.List.of(
                    new NameCase("NexGrid 1057", "18000001057", "Old", 0, "UVEL 1057"),
                    new NameCase("NexGrid 1057", "18000009999", "Old", 0, "NexGrid 1057"),
                    new NameCase("NexGrid 1057", null, "Old", 0, "NexGrid 1057"),
                    new NameCase("Custom NexGrid 1057", "18000001057", "Old", 0, "Custom NexGrid 1057"),
                    new NameCase("nexgrid 1057", "18000001057", "Old", 0, "nexgrid 1057"),
                    new NameCase("NexGrid 1057 ", "18000001057", "Old", 0, "NexGrid 1057 "),
                    new NameCase("NexGrid1057", "18000001057", "Old", 0, "NexGrid1057"),
                    new NameCase("NexGrid  1057", "18000001057", "Old", 0, "NexGrid  1057"),
                    new NameCase("Nexion3778", "18000003778", "Old", 0, "Nexion3778"),
                    new NameCase("NexGrid 10x7", "180000010x7", "Old", 0, "NexGrid 10x7"),
                    new NameCase(null, "18000001057", "NexGrid 1057", 0, "NexGrid 1057"),
                    new NameCase("", "18000001057", "NexGrid 1057", 0, ""),
                    new NameCase(null, "18000001057", null, 0, null),
                    new NameCase("NexGrid 1057", "18000001057", "NexGrid 1057", 1, "NexGrid 1057"));
            for (int index = 0; index < names.size(); index++) {
                var sample = names.get(index);
                long id = 500000000L + index;
                jdbc.update("INSERT INTO nx_user(id,nickname,is_deleted,phone) VALUES(?,?,?,?)", id, sample.live(), sample.deleted(), sample.phone());
                jdbc.update("INSERT INTO nx_team_member VALUES(?,8,? ,?,?, 'V1',1,19.90,NOW(),0)", id, id, "U" + id, sample.fallback());
            }
            jdbc.update("INSERT INTO nx_team_member VALUES(999,9,999,'legacy','NexGrid 1057','V1',1,0,NOW(),0)");

            Configuration configuration = new Configuration(
                    new Environment("isolated-c1-team", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(UserOpsMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                assertThat(session.getMapper(UserOpsMapper.class).teamMembers(7L, 20))
                        .singleElement()
                        .satisfies(member -> {
                            assertThat(member.memberUserId()).isEqualTo(60723153012L);
                            assertThat(member.memberNo()).isEqualTo("U60723153012");
                            assertThat(member.nickname()).isEqualTo("Turbo Pulse 36");
                        });
                var rows = session.getMapper(UserOpsMapper.class).teamMembers(8L, 30);
                assertThat(rows).hasSize(names.size());
                for (int index = 0; index < names.size(); index++) {
                    long id = 500000000L + index;
                    var sample = names.get(index);
                    var row = rows.stream().filter(member -> member.memberNo().equals("U" + id)).findFirst().orElseThrow();
                    assertThat(row.nickname()).as("nickname case %s", index).isEqualTo(sample.expected());
                    assertThat(row.volume()).isEqualByComparingTo("19.90");
                    assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?", String.class, id)).isEqualTo(sample.live());
                    assertThat(jdbc.queryForObject("SELECT nickname FROM nx_team_member WHERE id=?", String.class, id)).isEqualTo(sample.fallback());
                }
                assertThat(session.getMapper(UserOpsMapper.class).teamMembers(9L, 10)).singleElement()
                        .satisfies(member -> assertThat(member.nickname()).isEqualTo("NexGrid 1057"));
            }
        } finally {
            if (dataSource != null) dataSource.destroy();
            admin.execute("DROP DATABASE IF EXISTS " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
            System.out.println("BRAND_READ_SCHEMA_DROPPED " + schema);
        }
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
