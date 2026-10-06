package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.team.mapper.AppTeamInsightsMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Actual mapper-to-service reads, enabled only for an explicitly owned loopback UUID schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEAM_SUMMARY_IT", matches = "true")
class AppTeamInsightsMySqlRuntimeTest {
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 5, 23, 0, 0);

    @Test
    void newDirectKindsRemainDirectAcrossStatesOutsideTheCurrentPage() throws Exception {
        inSchema((jdbc, mapper) -> {
            long id = 1;
            for (String kind : List.of("direct", "NETWORK", "unilevel")) {
                int index = (int) ((id + 1) / 2);
                event(jdbc, id++, 7, kind, 1, String.valueOf(index), "0", "UNLOCKED", CUTOFF, 0);
                event(jdbc, id++, 7, kind, 2, String.valueOf(index * 10), "0", "FROZEN", CUTOFF.minusDays(1), 0);
            }
            int nonDirectAmount = 40;
            for (String kind : List.of("binary", "peer", "cultivation", "leadership", "genesis")) {
                event(jdbc, id++, 7, kind, 1, String.valueOf(nonDirectAmount), "0", "COOLING", CUTOFF.minusDays(1), 0);
                nonDirectAmount += 10;
            }
            for (String status : List.of("COOLING", "FROZEN", "UNLOCKED", "PAID", "REJECTED", "REVERSED", "RECOVERY_PENDING")) {
                for (String kind : List.of("DIRECT_PURCHASE", "direct_device_earning")) {
                    boolean purchase = kind.equals("DIRECT_PURCHASE");
                    event(jdbc, id++, 7, kind, 1, purchase ? "6" : "0.33", "0", status, CUTOFF.minusHours(1), 0);
                    event(jdbc, id++, 7, kind, 1, "0", purchase ? "400" : "22", status, CUTOFF.minusHours(1), 0);
                }
            }
            var result = new AppTeamInsightsService(mapper, mock(LeadershipPoolConfigGuard.class),
                    mock(PlatformConfigFacade.class), new MockEnvironment())
                    .commissions(7L, 1, 1, "2026-10-05T15:00:00Z").getData();
            writeEvidence("classification-service", Map.of("response", result, "rows", jdbc.queryForList("SELECT * FROM nx_commission_event ORDER BY id")));
            @SuppressWarnings("unchecked") var aggregate = (Map<String, Object>) result.get("aggregate");
            assertMoney(aggregate, "totalUSDT", "410.31");
            assertMoney(aggregate, "directUSDT", "50.31");
            assertMoney(aggregate, "extendedUSDT", "360");
            assertMoney(aggregate, "totalNEX", "2954");
            assertMoney(aggregate, "unlockedUSDT", "12.33");
            assertMoney(aggregate, "unlockedNEX", "422");
            assertMoney(aggregate, "coolingUSDT", "306.33");
            assertThat(aggregate).containsEntry("eventCount", 39).containsEntry("contributorCount", 1);
            @SuppressWarnings("unchecked") var byKind = (Map<String, Map<String, Object>>) aggregate.get("byKind");
            assertMoney(byKind.get("direct_purchase"), "usdt", "42");
            assertMoney(byKind.get("direct_purchase"), "nex", "2800");
            assertMoney(byKind.get("direct_device_earning"), "usdt", "2.31");
            assertMoney(byKind.get("direct_device_earning"), "nex", "154");
            assertThat(byKind.get("direct_purchase")).containsEntry("count", 14);
            assertThat(byKind.get("direct_device_earning")).containsEntry("count", 14);
            @SuppressWarnings("unchecked") var events = (List<Map<String, Object>>) result.get("events");
            assertThat(events).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("kind", "unilevel"));
            assertThat(result).containsEntry("page", 1L).containsEntry("pageSize", 1L).containsEntry("totalRows", 39L);
        });
    }

    @Test
    void summaryPreservesLegacyLayersCurrencyOwnerDeletionAndSnapshotBoundaries() throws Exception {
        inSchema((jdbc, mapper) -> {
            event(jdbc, 1, 7, "direct_purchase", 1, "6", "0", "REVERSED", CUTOFF, 0);
            event(jdbc, 2, 7, "direct_device_earning", 1, "0", "400", "RECOVERY_PENDING", CUTOFF, 0);
            long id = 3;
            for (String kind : List.of("direct", "network", "UNILEVEL")) {
                event(jdbc, id, 7, kind, 1, String.valueOf(id - 2), "0", "UNLOCKED", CUTOFF.minusSeconds(1), 0);
                id++;
                event(jdbc, id, 7, kind, 2, String.valueOf(id - 2), "0", "COOLING", CUTOFF.minusSeconds(1), 0);
                id++;
            }
            event(jdbc, 9, 7, "binary", 1, "7", "0", "UNLOCKED", CUTOFF.minusSeconds(1), 0);
            event(jdbc, 10, 7, "direct_purchase", 1, "1000", "1000", "UNLOCKED", CUTOFF.plusSeconds(1), 0);
            event(jdbc, 11, 7, "direct_device_earning", 1, "1000", "1000", "UNLOCKED", CUTOFF, 1);
            event(jdbc, 12, 8, "direct_device_earning", 1, "9", "600", "FROZEN", CUTOFF, 0);
            id = 13;
            for (String kind : List.of("direct_purchase", "direct_device_earning")) {
                for (Integer layer : java.util.Arrays.asList(null, 0, 2, 7)) {
                    event(jdbc, id++, 7, kind, layer, "10", "1", "UNLOCKED", CUTOFF, 0);
                }
            }
            var summary = mapper.commissionSummary(7L, CUTOFF);
            writeEvidence("classification-boundaries", Map.of("summary", summary, "otherOwner", mapper.commissionSummary(8L, CUTOFF),
                    "emptyOwner", mapper.commissionSummary(999L, CUTOFF), "rows", jdbc.queryForList("SELECT * FROM nx_commission_event ORDER BY id")));
            assertThat(summary.totalUsdt()).isEqualByComparingTo("114");
            assertThat(summary.directUsdt()).isEqualByComparingTo("15");
            assertThat(summary.extendedUsdt()).isEqualByComparingTo("99");
            assertThat(summary.totalNex()).isEqualByComparingTo("408");
            assertThat(summary.totalUsdt()).isEqualByComparingTo(summary.directUsdt().add(summary.extendedUsdt()));
            assertThat(mapper.commissionEventCount(7L, CUTOFF)).isEqualTo(17);
            assertThat(mapper.commissionSummary(8L, CUTOFF).directUsdt()).isEqualByComparingTo("9");
            assertThat(mapper.commissionSummary(8L, CUTOFF).extendedUsdt()).isZero();
            var empty = mapper.commissionSummary(999L, CUTOFF);
            assertThat(empty.totalUsdt()).isZero(); assertThat(empty.totalNex()).isZero();
            assertThat(empty.directUsdt()).isZero(); assertThat(empty.extendedUsdt()).isZero();
        });
    }

    private static void event(JdbcTemplate jdbc, long id, long owner, String kind, Integer layer, String usdt,
                              String nex, String status, LocalDateTime at, int deleted) {
        jdbc.update("INSERT INTO nx_commission_event VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, owner, kind, 18,
                "Source", layer, "ORD-" + id, new BigDecimal("100"), new BigDecimal(usdt), new BigDecimal(nex), status, at, at.plusDays(30), deleted);
    }

    private static void assertMoney(Map<String, Object> values, String key, String expected) {
        assertThat((BigDecimal) values.get(key)).as(key).isEqualByComparingTo(expected);
    }

    private static void writeEvidence(String name, Object values) throws Exception {
        String directory = System.getenv("NEXION_TEAM_SUMMARY_EVIDENCE_DIR");
        if (directory == null) return;
        Path path = Path.of(directory); Files.createDirectories(path);
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(path.resolve(name + ".json").toFile(), values);
    }

    private static void inSchema(Fixture test) throws Exception {
        String url = System.getenv("NEXION_TEAM_SUMMARY_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/nexion_team_summary_it_[a-f0-9]{32}\\?.+"))
            throw new IllegalArgumentException("owned loopback UUID schema required");
        var driver = new DriverManagerDataSource(url, System.getenv("NEXION_ISOLATED_MYSQL_USERNAME"), System.getenv("NEXION_ISOLATED_MYSQL_PASSWORD"));
        var dataSource = new SingleConnectionDataSource(driver.getConnection(), true);
        try {
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(url.substring(url.lastIndexOf('/') + 1, url.indexOf('?')));
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
            jdbc.execute("CREATE TABLE IF NOT EXISTS nx_user(id BIGINT PRIMARY KEY,nickname VARCHAR(120),sandbox TINYINT,status VARCHAR(16),is_deleted TINYINT,v_rank VARCHAR(8))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS nx_commission_event(id BIGINT PRIMARY KEY,user_id BIGINT,commission_type VARCHAR(32),source_user_id BIGINT,source_user_name VARCHAR(120),layer_no INT,order_no VARCHAR(80),order_amount_usd DECIMAL(20,6),amount_usdt DECIMAL(20,6),amount_nex DECIMAL(20,6),status VARCHAR(32),created_at DATETIME,unlock_at DATETIME,is_deleted TINYINT)");
            jdbc.update("DELETE FROM nx_commission_event"); jdbc.update("DELETE FROM nx_user");
            jdbc.update("INSERT INTO nx_user VALUES(7,'Owner',0,'ACTIVE',0,'V0'),(8,'Other',0,'ACTIVE',0,'V0'),(18,'Source',0,'ACTIVE',0,'V0')");
            var configuration = new Configuration(new Environment("isolated-team-summary", new JdbcTransactionFactory(), dataSource));
            configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(AppTeamInsightsMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                test.run(jdbc, session.getMapper(AppTeamInsightsMapper.class));
            }
        } finally {dataSource.destroy();}
    }

    @FunctionalInterface private interface Fixture { void run(JdbcTemplate jdbc, AppTeamInsightsMapper mapper) throws Exception; }
}
