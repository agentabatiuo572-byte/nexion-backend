package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.team.application.AppTeamNetworkService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

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

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void genericCommissionSourceNamesPreserveSnapshotsRowsNamespaceAndPagination() throws Exception {
        inSchema((jdbc, configuration) -> {
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(120),sandbox TINYINT,status VARCHAR(16),is_deleted TINYINT)");
            jdbc.execute("CREATE TABLE nx_commission_event(id BIGINT PRIMARY KEY,user_id BIGINT,commission_type VARCHAR(32),source_user_id BIGINT,source_user_name VARCHAR(120),layer_no INT,order_no VARCHAR(80),order_amount_usd DECIMAL(20,6),amount_usdt DECIMAL(20,6),amount_nex DECIMAL(20,6),status VARCHAR(32),created_at DATETIME,unlock_at DATETIME,is_deleted TINYINT)");
            jdbc.update("INSERT INTO nx_user VALUES(7,'Beneficiary',0,'ACTIVE',0),(8,'NexGrid 1051',0,'ACTIVE',0),(9,'Deleted source',0,'ACTIVE',1),(10,'Other namespace',1,'ACTIVE',0),(11,'',0,'ACTIVE',0),(12,NULL,0,'ACTIVE',0),(13,'Former source',0,'INACTIVE',0),(17,'Sandbox beneficiary',1,'ACTIVE',0),(18,'Sandbox source',1,'ACTIVE',0)");
            var cutoff = LocalDateTime.of(2026, 10, 5, 19, 48, 21);
            record SourceCase(long id, Long sourceId, String snapshot, String expected) { }
            var cases = List.of(
                    new SourceCase(1, 8L, null, "NexGrid 1051"),
                    new SourceCase(2, 8L, "", "NexGrid 1051"),
                    new SourceCase(3, 8L, " \t\n", "NexGrid 1051"),
                    new SourceCase(4, 8L, " Original snapshot ", " Original snapshot "),
                    new SourceCase(5, 9L, null, null),
                    new SourceCase(6, 10L, null, null),
                    new SourceCase(7, null, null, null),
                    new SourceCase(8, 999L, null, null),
                    new SourceCase(9, 11L, null, ""),
                    new SourceCase(10, 12L, null, null),
                    new SourceCase(11, 9L, "Saved before deletion", "Saved before deletion"),
                    new SourceCase(12, 13L, null, "Former source"));
            String insert = "INSERT INTO nx_commission_event VALUES(?,?,'network',?,?,1,?,19.9,1.99,0,'COOLING',?,?,?)";
            for (var sample : cases) {
                jdbc.update(insert, sample.id(), 7L, sample.sourceId(), sample.snapshot(), "ORD-" + sample.id(),
                        cutoff, cutoff.plusDays(30), 0);
            }
            jdbc.update(insert, 50L, 17L, 18L, null, "SANDBOX-ORDER", cutoff, cutoff.plusDays(30), 0);
            jdbc.update(insert, 60L, 7L, 8L, null, "DELETED-EVENT", cutoff, cutoff.plusDays(30), 1);
            jdbc.update(insert, 61L, 7L, 8L, null, "AFTER-SNAPSHOT", cutoff.plusSeconds(1), cutoff.plusDays(30), 0);
            jdbc.update(insert, 62L, 99L, 8L, null, "MISSING-BENEFICIARY", cutoff, cutoff.plusDays(30), 0);
            configuration.addMapper(AppTeamInsightsMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(AppTeamInsightsMapper.class);
                var rows = mapper.commissionEvents(7L, cutoff, 0, 100);
                assertThat(rows).hasSize(cases.size());
                for (var sample : cases) {
                    var row = rows.stream().filter(item -> item.id() == sample.id()).findFirst().orElseThrow();
                    assertThat(row.sourceUserName()).as("source name for event %s", sample.id()).isEqualTo(sample.expected());
                    assertThat(row.amountUsdt()).isEqualByComparingTo("1.99");
                    assertThat(row.amountNex()).isZero();
                    assertThat(row.status()).isEqualTo("COOLING");
                    assertThat(row.unlockAt()).isEqualTo(cutoff.plusDays(30));
                }
                assertThat(mapper.commissionEventCount(7L, cutoff)).isEqualTo(cases.size());
                assertThat(mapper.commissionEvents(7L, cutoff, 1, 2))
                        .extracting(AppTeamInsightsMapper.CommissionRow::id).containsExactly(11L, 10L);
                assertThat(mapper.commissionEvents(17L, cutoff, 0, 100))
                        .extracting(AppTeamInsightsMapper.CommissionRow::sourceUserName).containsExactly("Sandbox source");
                assertThat(mapper.commissionEvents(99L, cutoff, 0, 100)).hasSize(1)
                        .allSatisfy(row -> assertThat(row.sourceUserName()).isNull());
            }
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void networkMonthlyVolumeReadsOwnQualifyingOrderSubtotalInsteadOfStaleProjection() throws Exception {
        inSchema((jdbc, configuration) -> {
            networkTables(jdbc);
            jdbc.update("INSERT INTO nx_user(id,sponsor_user_id) VALUES(7,NULL),(18,7),(19,7)");
            jdbc.update("INSERT INTO nx_team_member VALUES(1,7,18,0,0),(2,7,19,0,0)");
            jdbc.update("INSERT INTO nx_binary_leg_assignment VALUES(7,18,'A'),(7,19,'B')");
            jdbc.update("INSERT INTO nx_order VALUES(1,18,19.9,9.9,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(2,7,4995,4995,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00')");
            configuration.addMapper(AppTeamNetworkMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(AppTeamNetworkMapper.class);
                var rows = mapper.membersPage(7L, 0, 501);
                assertThat(rows).extracting(AppTeamNetworkMapper.MemberRow::memberUserId).containsExactly(18L, 19L);
                assertThat(rows.get(0).monthVolumeUsdt()).isEqualByComparingTo("19.9");
                assertThat(rows.get(1).monthVolumeUsdt()).isZero();
                assertThat(rows).allSatisfy(row -> assertThat(row.lifetimeVolumeUsdt()).isNull());
                jdbc.update("INSERT INTO nx_order VALUES(3,18,100,100,'PENDING','CREATED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(4,18,100,100,'FAILED','CREATED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(5,18,100,100,'PAID','REFUNDED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(6,18,100,100,'PAID','CHARGEBACK',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(7,18,100,100,'PAID','COMPLETED',1,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(8,18,100,100,'PAID','COMPLETED',0,'2026-09-30 08:00:00','2026-09-30 08:00:00'),(9,19,100,100,'PAID','REFUNDED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00')");
                // Bypass the session query cache after changing the real SQL fixture.
                session.clearCache();
                var data = new AppTeamNetworkService(mapper, new MockEnvironment()).snapshot(7L).getData();
                assertThat(data).containsEntry("totalMembers", 2).containsEntry("directMembers", 2);
                assertThat(new BigDecimal(data.get("monthVolumeUsdt").toString())).isEqualByComparingTo("19.9");
                assertThat(mapper.membersPage(7L, 18, 501)).hasSize(1)
                        .allSatisfy(row -> assertThat(row.monthVolumeUsdt()).isZero());
            }
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void networkMonthIsHalfOpenUtcWithBusinessDatetimeAndPaidAtFallbackOnNonUtcSessions() throws Exception {
        inSchema((jdbc, configuration) -> {
            networkTables(jdbc);
            jdbc.update("INSERT INTO nx_user(id,sponsor_user_id) VALUES(7,NULL),(18,7)");
            jdbc.update("INSERT INTO nx_order VALUES(1,18,2,1,'PAID','COMPLETED',0,'2026-10-01 08:00:00','2026-09-01 08:00:00'),(2,18,3,1,'PAID','COMPLETED',0,'2026-11-01 07:59:59','2026-10-05 08:00:00'),(3,18,100,1,'PAID','COMPLETED',0,'2026-10-01 07:59:59','2026-10-05 08:00:00'),(4,18,100,1,'PAID','COMPLETED',0,'2026-11-01 08:00:00','2026-10-05 08:00:00'),(5,18,5,1,'SUCCESS','COMPLETED',0,NULL,'2026-10-01 08:00:00'),(6,18,100,1,'PAID','COMPLETED',0,NULL,'2026-10-01 07:59:59'),(7,18,100,1,'PAID','COMPLETED',0,NULL,'2026-11-01 08:00:00'),(8,18,7,1,'CONFIRMED','COMPLETED',0,'2026-10-05 08:00:00','2026-09-01 08:00:00'),(9,18,100,1,'PAID','COMPLETED',0,'2026-09-30 08:00:00','2026-10-05 08:00:00')");
            configuration.addMapper(AppTeamNetworkMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(AppTeamNetworkMapper.class);
                for (String zone : List.of("+07:00", "-05:00")) {
                    jdbc.execute("SET time_zone = '" + zone + "'");
                    jdbc.execute("SET timestamp = " + Instant.parse("2026-10-01T00:00:00Z").getEpochSecond());
                    assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%d %H:%i:%s')", String.class))
                            .isEqualTo("2026-10-01 00:00:00");
                    session.clearCache();
                    assertThat(mapper.membersPage(7L, 0, 501)).hasSize(1)
                            .allSatisfy(row -> assertThat(row.monthVolumeUsdt()).as("session %s", zone).isEqualByComparingTo("17"));
                }
            }
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_TEAM_READ_IT", matches = "true")
    void networkPreservesOwnerNamespaceDepthCycleAndPaginationWithoutProjectionDuplicates() throws Exception {
        inSchema((jdbc, configuration) -> {
            networkTables(jdbc);
            jdbc.update("INSERT INTO nx_user(id,sponsor_user_id) VALUES(7,18),(18,7),(19,7),(20,18),(21,20),(22,21),(23,22),(24,23),(25,24),(26,25),(50,NULL),(51,50),(40,7),(41,40),(42,7),(43,42),(70,NULL),(71,70),(72,7)");
            jdbc.update("UPDATE nx_user SET is_deleted=1 WHERE id=40");
            jdbc.update("UPDATE nx_user SET status='INACTIVE' WHERE id=42");
            jdbc.update("UPDATE nx_user SET sandbox=1 WHERE id IN (70,71,72)");
            // Stale and duplicate team projections must not duplicate canonical user/order rows.
            jdbc.update("INSERT INTO nx_team_member VALUES(1,7,18,888,0),(2,7,18,999,0),(3,50,18,777,0),(4,7,19,666,1)");
            jdbc.update("INSERT INTO nx_binary_leg_assignment VALUES(7,18,'A'),(7,19,'B'),(50,18,'B'),(70,71,'A')");
            jdbc.update("INSERT INTO nx_order VALUES(1,18,19.9,19.9,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(2,20,3,3,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(3,25,5,5,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(4,26,100,100,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(5,51,100,100,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(6,40,100,100,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(7,42,100,100,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(8,71,11,11,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00'),(9,72,100,100,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00')");
            configuration.addMapper(AppTeamNetworkMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                var mapper = session.getMapper(AppTeamNetworkMapper.class);
                var rows = mapper.membersPage(7L, 0, 501);
                assertThat(rows).extracting(AppTeamNetworkMapper.MemberRow::memberUserId)
                        .containsExactly(18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L);
                assertThat(rows.get(0).monthVolumeUsdt()).isEqualByComparingTo("19.9");
                assertThat(rows.get(2).monthVolumeUsdt()).isEqualByComparingTo("3");
                assertThat(rows.get(7).monthVolumeUsdt()).isEqualByComparingTo("5");
                assertThat(rows.get(7).level()).isEqualTo(7);
                assertThat(rows).filteredOn(row -> row.memberUserId() != 19L)
                        .allSatisfy(row -> assertThat(row.leg()).isEqualTo("A"));
                assertThat(rows.get(1).leg()).isEqualTo("B");
                List<AppTeamNetworkMapper.MemberRow> paged = new ArrayList<>();
                for (long cursor = 0; ; ) {
                    var page = mapper.membersPage(7L, cursor, 2);
                    if (page.isEmpty()) break;
                    paged.addAll(page);
                    cursor = page.get(page.size() - 1).memberUserId();
                }
                assertThat(paged).isEqualTo(rows);
                assertThat(mapper.membersPage(50L, 0, 501)).hasSize(1)
                        .allSatisfy(row -> assertThat(row.memberUserId()).isEqualTo(51L));
                assertThat(mapper.membersPage(70L, 0, 501)).hasSize(1)
                        .allSatisfy(row -> assertThat(row.monthVolumeUsdt()).isEqualByComparingTo("11"));
                for (long owner : new long[]{40, 42, 999}) assertThat(mapper.membersPage(owner, 0, 501)).isEmpty();
                var service = new AppTeamNetworkService(mapper, new MockEnvironment());
                assertThat(new BigDecimal(service.snapshot(7L).getData().get("monthVolumeUsdt").toString()))
                        .isEqualByComparingTo("27.9");
                for (long id = 1000; id < 1510; id++) {
                    jdbc.update("INSERT INTO nx_user(id,sponsor_user_id) VALUES(?,7)", id);
                    jdbc.update("INSERT INTO nx_order VALUES(?,?,0.01,0.01,'PAID','COMPLETED',0,'2026-10-05 08:00:00','2026-10-05 08:00:00')", id, id);
                }
                session.clearCache();
                var first = service.snapshot(7L).getData();
                assertThat(first).containsEntry("totalMembers", 500).containsEntry("directMembers", 494)
                        .containsEntry("nextCursor", "1491");
                assertThat(new BigDecimal(first.get("monthVolumeUsdt").toString())).isEqualByComparingTo("32.82");
                var second = service.snapshot(7L, 1491).getData();
                assertThat(second).containsEntry("totalMembers", 18).containsEntry("directMembers", 18)
                        .containsEntry("nextCursor", null);
                assertThat(new BigDecimal(second.get("monthVolumeUsdt").toString())).isEqualByComparingTo("0.18");
                assertThat(mapper.membersPage(7L, 1510, 501)).isEmpty();
            }
        });
    }

    private static void networkTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,sponsor_user_id BIGINT,nickname VARCHAR(120),avatar_url VARCHAR(255),v_rank VARCHAR(8),created_at DATETIME DEFAULT '2026-10-05 08:00:00',region VARCHAR(32),sandbox TINYINT DEFAULT 0,status VARCHAR(16) DEFAULT 'ACTIVE',is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_team_member(id BIGINT PRIMARY KEY,user_id BIGINT,member_user_id BIGINT,volume DECIMAL(20,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_binary_leg_assignment(owner_user_id BIGINT,member_user_id BIGINT,leg CHAR(1),UNIQUE KEY uk_binary_leg_owner_member(owner_user_id,member_user_id))");
        jdbc.execute("CREATE TABLE nx_order(id BIGINT PRIMARY KEY,user_id BIGINT,subtotal_usdt DECIMAL(18,6),amount_usdt DECIMAL(18,6),payment_status VARCHAR(32),order_status VARCHAR(32),is_deleted TINYINT,paid_at DATETIME,created_at DATETIME,KEY idx_order_user_time(user_id,created_at))");
        jdbc.execute("SET time_zone = '+07:00'");
        jdbc.execute("SET timestamp = " + Instant.parse("2026-10-05T00:00:00Z").getEpochSecond());
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
