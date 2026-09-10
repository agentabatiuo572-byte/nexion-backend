package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real mapper projections in one explicitly disposable UUID schema, never the business database. */
class TeamReadModelsMySqlTest {
    private static final String PREFIX = "nexion_team_read_it_";

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
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void quotaProjectionPreservesCriteriaAndHalfOpenUtcMonthOnNonUtcSession() throws Exception {
        inSchema((jdbc, configuration) -> {
            jdbc.execute("CREATE TABLE nx_team_hardware_quota_tier(id BIGINT PRIMARY KEY,quota_code VARCHAR(32),product_no VARCHAR(64),display_name VARCHAR(100),direct_refs INT,month_volume_usd DECIMAL(20,2),unlock_mode VARCHAR(16),monthly_quota INT,sort_order INT,is_deleted TINYINT,status INT)");
            jdbc.execute("CREATE TABLE nx_team_hardware_quota_usage(id BIGINT PRIMARY KEY,quota_tier_id BIGINT,quantity INT,occurred_at DATETIME,is_deleted TINYINT,status VARCHAR(32))");
            jdbc.update("INSERT INTO nx_team_hardware_quota_tier VALUES(1,'PRO','P-REAL','Pro',8,725.50,'EITHER',10,1,0,1),(2,'RACK','P-RACK','Rack',4,1200,'ALL',10,2,0,1),(3,'OFF','P-OFF','Off',0,0,'ALL',10,3,0,0),(4,'DEL','P-DEL','Deleted',0,0,'ALL',10,4,1,1)");
            jdbc.update("INSERT INTO nx_team_hardware_quota_usage VALUES(1,1,40,'2026-08-31 23:59:59',0,'ACTIVE'),(2,1,8,'2026-09-01 00:00:00',0,'ACTIVE'),(3,1,1,'2026-09-30 23:59:59',0,'active'),(4,1,100,'2026-10-01 00:00:00',0,'ACTIVE'),(5,1,100,'2026-09-01 00:00:00',1,'ACTIVE'),(6,1,100,'2026-09-01 00:00:00',0,'CANCELLED')");
            jdbc.execute("SET time_zone = '+07:00'");
            jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2026-09-01 07:00:00')");
            configuration.addMapper(TeamCommissionMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var rows = session.getMapper(TeamCommissionMapper.class).quotaRows();
                assertThat(rows).hasSize(2);
                var pro = rows.get(0);
                assertThat(pro).containsEntry("productNo", "P-REAL").containsEntry("directRefs", 8)
                        .containsEntry("monthVolumeUsd", new BigDecimal("725.50")).containsEntry("unlockMode", "EITHER");
                assertThat(new BigDecimal(pro.get("current").toString())).isEqualByComparingTo("9");
                assertThat(pro.get("tight").toString()).isIn("1", "true");
                assertThat(new BigDecimal(rows.get(1).get("current").toString())).isZero();
                assertThat(rows.get(1).get("tight").toString()).isIn("0", "false");
            }
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void catalogNamesAreExactAndExcludeDeletedRows() throws Exception {
        inSchema((jdbc, configuration) -> {
            jdbc.execute("CREATE TABLE nx_growth_voucher(voucher_id VARCHAR(80) PRIMARY KEY,voucher_name VARCHAR(120),is_deleted TINYINT)");
            jdbc.execute("CREATE TABLE nx_admin_device_sku(sku_id VARCHAR(64) PRIMARY KEY,name VARCHAR(128),is_deleted TINYINT)");
            jdbc.update("INSERT INTO nx_growth_voucher VALUES('V-1','Welcome voucher',0),('V-DEL','Deleted voucher',1)");
            jdbc.update("INSERT INTO nx_admin_device_sku VALUES('SKU-1','NexGridBox S1',0),('SKU-DEL','Deleted SKU',1)");
            configuration.addMapper(AppTeamInsightsMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(AppTeamInsightsMapper.class);
                assertThat(mapper.voucherDisplayName("V-1")).isEqualTo("Welcome voucher");
                assertThat(mapper.skuDisplayName("SKU-1")).isEqualTo("NexGridBox S1");
                for (String id : new String[]{"missing", "V-DEL", "SKU-DEL", "' OR 1=1 --"}) {
                    assertThat(mapper.voucherDisplayName(id)).isNull();
                    assertThat(mapper.skuDisplayName(id)).isNull();
                }
            }
        });
    }

    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}")))
            throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static void inSchema(SchemaTest test) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url(endpoint, ""), "root", ""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        admin.execute("CREATE DATABASE " + schema);
        SingleConnectionDataSource dataSource = null;
        try {
            dataSource = new SingleConnectionDataSource(new DriverManagerDataSource(url(endpoint, schema), "root", "").getConnection(), true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
            test.run(jdbc, new Configuration(new Environment("isolated-team-read", new JdbcTransactionFactory(), dataSource)));
        } finally {
            if (dataSource != null) dataSource.destroy();
            admin.execute("DROP DATABASE " + schema);
        }
    }

    @FunctionalInterface private interface SchemaTest { void run(JdbcTemplate jdbc, Configuration configuration) throws Exception; }
}
