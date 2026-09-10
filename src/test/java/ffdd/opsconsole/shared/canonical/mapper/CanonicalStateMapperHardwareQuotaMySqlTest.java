package ffdd.opsconsole.shared.canonical.mapper;

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

/** Real record-constructor and UTC-month mapping, with deterministic disposable F4b rows. */
class CanonicalStateMapperHardwareQuotaMySqlTest {
    private static final String PREFIX = "nx_f4b_quota_rows_it_";

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
    @EnabledIfEnvironmentVariable(named = "NEXION_F4B_QUOTA_ROWS_IT", matches = "true")
    void mapsDecimalNullableAndDisabledRulesWithHalfOpenUtcMonthUsage() throws Exception {
        inSchema((jdbc, configuration) -> {
            jdbc.execute("CREATE TABLE nx_team_hardware_quota_tier(id BIGINT PRIMARY KEY,quota_code VARCHAR(64),"
                    + "product_no VARCHAR(64),direct_refs INT,month_volume_usd DECIMAL(20,6),monthly_quota INT,"
                    + "unlock_mode VARCHAR(16),status INT,is_deleted TINYINT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE nx_team_hardware_quota_usage(id BIGINT PRIMARY KEY,quota_tier_id BIGINT,"
                    + "quantity INT,occurred_at DATETIME,status VARCHAR(20),is_deleted TINYINT) ENGINE=InnoDB");
            jdbc.update("INSERT INTO nx_team_hardware_quota_tier VALUES"
                    + "(10,'EXACT','SKU-X',3,725.123456,7,'EITHER',1,0),"
                    + "(20,'NULLABLE','SKU-X',NULL,NULL,NULL,'ALL',0,0),"
                    + "(30,'DELETED','SKU-X',9,900,9,'ALL',1,1),"
                    + "(40,'OTHER-SKU','SKU-Y',4,400,4,'ALL',1,0),"
                    + "(50,'NO-USAGE','SKU-X',1,10,2,'ALL',1,0)");
            jdbc.update("INSERT INTO nx_team_hardware_quota_usage VALUES"
                    + "(1,10,40,'2026-08-31 23:59:59','ACTIVE',0),"
                    + "(2,10,2,'2026-09-01 00:00:00','ACTIVE',0),"
                    + "(3,10,3,'2026-09-30 23:59:59','active',0),"
                    + "(4,10,100,'2026-10-01 00:00:00','ACTIVE',0),"
                    + "(5,10,100,'2026-09-10 00:00:00','ACTIVE',1),"
                    + "(6,10,100,'2026-09-10 00:00:00','CANCELLED',0)");
            jdbc.execute("SET time_zone='+07:00'");
            // Local October must still count September while UTC is September 30.
            jdbc.execute("SET timestamp=UNIX_TIMESTAMP('2026-10-01 00:30:00')");
            configuration.addMapper(CanonicalStateMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(CanonicalStateMapper.class);
                var rows = mapper.purchaseHardwareQuotas("SKU-X");
                assertThat(rows).extracting(CanonicalStateMapper.HardwareQuota::quotaCode)
                        .containsExactly("EXACT", "NULLABLE", "NO-USAGE");
                var exact = rows.get(0);
                assertThat(exact.directRefs()).isEqualTo(3);
                assertThat(exact.monthVolumeUsd()).isEqualByComparingTo("725.123456");
                assertThat(exact.monthlyQuota()).isEqualTo(7);
                assertThat(exact.usedThisMonth()).isEqualTo(5L);
                assertThat(exact.unlockMode()).isEqualTo("EITHER");
                assertThat(exact.status()).isEqualTo(1);
                var nullable = rows.get(1);
                assertThat(nullable.directRefs()).isNull();
                assertThat(nullable.monthVolumeUsd()).isNull();
                assertThat(nullable.monthlyQuota()).isNull();
                assertThat(nullable.usedThisMonth()).isZero();
                assertThat(nullable.status()).isZero();
                assertThat(rows.get(2).usedThisMonth()).isZero();
                assertThat(mapper.purchaseHardwareQuotas("SKU-X' OR 1=1 --")).isEmpty();
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
            Configuration configuration = new Configuration(new Environment("f4b-fixture", new JdbcTransactionFactory(), dataSource));
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
