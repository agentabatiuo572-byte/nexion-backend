package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** Real network mapper against the existing disposable MySQL fixture endpoint. */
class AppTeamNetworkBrandMySqlTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_NETWORK_BRAND_IT", matches = "true")
    void exactDefaultNamesPreserveCustomNamesScopeAndBinaryMembers() throws Exception {
        assertThat(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT")).isEqualTo("127.0.0.1:13306");
        String schema = "nx_network_brand_it_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:mysql://127.0.0.1:13306/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        System.out.println("NETWORK_BRAND_SCHEMA_CREATED " + schema);
        try (var connection = new DriverManagerDataSource(url, "root", "").getConnection()) {
            connection.setCatalog(schema);
            var dataSource = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),phone VARCHAR(32),sponsor_user_id BIGINT,status VARCHAR(20) DEFAULT 'ACTIVE',is_deleted INT DEFAULT 0,sandbox INT DEFAULT 0,avatar_url VARCHAR(100),v_rank VARCHAR(10) DEFAULT 'V1',created_at DATETIME DEFAULT CURRENT_TIMESTAMP,region VARCHAR(20))");
            jdbc.execute("CREATE TABLE nx_binary_leg_assignment(owner_user_id BIGINT,member_user_id BIGINT,leg VARCHAR(10))");
            jdbc.execute("CREATE TABLE nx_order(user_id BIGINT,compute_points BIGINT,payment_status VARCHAR(20),order_status VARCHAR(20),is_deleted INT,paid_at DATETIME,created_at DATETIME)");
            jdbc.update("INSERT INTO nx_user(id,nickname) VALUES(1,'Owner')");
            record NameCase(String raw, String phone, String expected) { }
            var names = List.of(
                    new NameCase("NexGrid-F343FA44", null, "UVEL-F343FA44"),
                    new NameCase("NexGrid-f343fa44", null, "NexGrid-f343fa44"),
                    new NameCase("NexGrid-F343FA44 ", null, "NexGrid-F343FA44 "),
                    new NameCase("NexGrid-F343FA4G", null, "NexGrid-F343FA4G"),
                    new NameCase("NexGrid 1051", "18000001051", "UVEL 1051"),
                    new NameCase("NexGrid 1055", "18000001055", "UVEL 1055"),
                    new NameCase("NexGrid 1051", "18000009999", "NexGrid 1051"),
                    new NameCase("NexGrid 1051", null, "NexGrid 1051"),
                    new NameCase("Custom NexGrid 1051", "18000001051", "Custom NexGrid 1051"),
                    new NameCase("nexgrid 1051", "18000001051", "nexgrid 1051"),
                    new NameCase("NexGrid 1051 ", "18000001051", "NexGrid 1051 "),
                    new NameCase("NexGrid1051", "18000001051", "NexGrid1051"),
                    new NameCase("NexGrid  1051", "18000001051", "NexGrid  1051"),
                    new NameCase("Nexion3778", "18000003778", "Nexion3778"),
                    new NameCase("NexGrid 10x1", "180000010x1", "NexGrid 10x1"),
                    new NameCase("Nex***", "18000001051", "Nex***"),
                    new NameCase(null, "18000001051", null),
                    new NameCase("", "18000001051", ""),
                    new NameCase("UVEL 1051", "18000001051", "UVEL 1051"));
            for (int i = 0; i < names.size(); i++) {
                var sample = names.get(i);
                jdbc.update("INSERT INTO nx_user(id,nickname,phone,sponsor_user_id) VALUES(?,?,?,1)", 100 + i, sample.raw(), sample.phone());
                jdbc.update("INSERT INTO nx_binary_leg_assignment VALUES(1,?,?)", 100 + i, i % 2 == 0 ? "A" : "B");
                jdbc.update("INSERT INTO nx_order VALUES(?,199,'PAID','COMPLETED',0,DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-01'),INTERVAL 9 HOUR),NOW())", 100 + i);
            }
            jdbc.update("INSERT INTO nx_user(id,nickname,phone,sponsor_user_id,sandbox,status,is_deleted) VALUES(900,'NexGrid 1051','18000001051',1,1,'ACTIVE',0),(901,'NexGrid 1051','18000001051',1,0,'DISABLED',0),(902,'NexGrid 1051','18000001051',1,0,'ACTIVE',1),(903,'NexGrid 1051','18000001051',900,1,'ACTIVE',0)");
            Configuration config = new Configuration(new Environment("network-brand-owned", new JdbcTransactionFactory(), dataSource));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(AppTeamNetworkMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(AppTeamNetworkMapper.class);
                var rows = mapper.membersPage(1L, 0, 501);
                assertThat(rows).hasSize(names.size());
                for (int i = 0; i < names.size(); i++) {
                    var row = rows.get(i);
                    assertThat(row.memberUserId()).isEqualTo(100L + i);
                    assertThat(row.nickname()).as("name boundary %s", i).isEqualTo(names.get(i).expected());
                    assertThat(row.leg()).isEqualTo(i % 2 == 0 ? "A" : "B");
                    assertThat(row.level()).isEqualTo(1);
                    assertThat(row.monthComputePoints()).isEqualTo(199L);
                    assertThat(jdbc.queryForObject("SELECT nickname FROM nx_user WHERE id=?", String.class, 100 + i)).isEqualTo(names.get(i).raw());
                }
                assertThat(mapper.membersPage(1L, 105, 2)).extracting(AppTeamNetworkMapper.MemberRow::memberUserId).containsExactly(106L, 107L);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user", Integer.class)).isEqualTo(names.size() + 5);
            }
        } finally {
            admin.execute("DROP DATABASE " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
            System.out.println("NETWORK_BRAND_SCHEMA_DROPPED " + schema);
        }
    }
}
