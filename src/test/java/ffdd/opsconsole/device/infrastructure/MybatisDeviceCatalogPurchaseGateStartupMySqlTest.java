package ffdd.opsconsole.device.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
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

/** Actual purchase-gate mapper SQL and startup branch; unrelated startup domains are not executed. */
@EnabledIfEnvironmentVariable(named = "NEXION_E1_GATE_STARTUP_IT", matches = "true")
class MybatisDeviceCatalogPurchaseGateStartupMySqlTest {
    @Test
    void existingColumnKeepsNullBlankCustomAndDeletedRowsAcrossRestarts() throws Exception {
        run((jdbc, mapper, repository) -> {
            mapper.createSkuTable();
            jdbc.update("INSERT INTO nx_admin_device_sku(sku_id,name,purchase_gate_json,updated_at) VALUES('stellarbox-pro-v2','Pro',NULL,'2026-10-01'),('stellarrack-p2','Rack','  ','2026-10-01'),('other','Other','{\"rankMin\":7}','2026-10-01')");
            var before = jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id");
            repository.ensureSchema();
            repository.ensureSchema();
            assertThat(jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id")).isEqualTo(before);
            jdbc.update("UPDATE nx_admin_device_sku SET purchase_gate_json='{\"rankMin\":9}' WHERE sku_id='stellarbox-pro-v2'");
            jdbc.update("UPDATE nx_admin_device_sku SET is_deleted=1,purchase_gate_json=NULL WHERE sku_id='stellarrack-p2'");
            before = jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id");
            repository.ensureSchema();
            assertThat(jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id")).isEqualTo(before);
        });
    }

    @Test
    void missingColumnSeedsLegacyDefaultsOnceThenMapperClearSurvivesRestart() throws Exception {
        run((jdbc, mapper, repository) -> {
            mapper.createSkuTable();
            jdbc.execute("ALTER TABLE nx_admin_device_sku DROP COLUMN purchase_gate_json");
            jdbc.update("INSERT INTO nx_admin_device_sku(sku_id,name) VALUES('stellarbox-pro-v2','Pro'),('stellarrack-p2','Rack'),('other','Other')");
            repository.ensureSchema();
            assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(purchase_gate_json,'$.rankMin') FROM nx_admin_device_sku WHERE sku_id='stellarbox-pro-v2'", String.class)).isEqualTo("2");
            assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(purchase_gate_json,'$.rankMin') FROM nx_admin_device_sku WHERE sku_id='stellarrack-p2'", String.class)).isEqualTo("4");
            assertThat(jdbc.queryForObject("SELECT purchase_gate_json FROM nx_admin_device_sku WHERE sku_id='other'", String.class)).isNull();
            var json = new ObjectMapper().findAndRegisterModules();
            var write = json.convertValue(Map.of("skuId", "stellarbox-pro-v2", "name", "Pro", "price", 2639,
                    "dailyEarn", 0, "dailyEarnNex", 0, "stock", "3", "status", "on",
                    "updatedAt", LocalDateTime.of(2026, 10, 17, 13, 29)), DeviceCatalogMapper.SkuWrite.class);
            assertThat(write.purchaseGateJson()).isNull();
            mapper.upsertSkuMetadata(write);
            assertThat(jdbc.queryForObject("SELECT purchase_gate_json FROM nx_admin_device_sku WHERE sku_id='stellarbox-pro-v2'", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT purchase_gate_generation FROM nx_admin_device_sku WHERE sku_id='stellarbox-pro-v2'", Long.class)).isEqualTo(2L);
            var before = jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id");
            repository.ensureSchema();
            repository.ensureSchema();
            assertThat(jdbc.queryForList("SELECT * FROM nx_admin_device_sku ORDER BY sku_id")).isEqualTo(before);
        });
    }

    private void run(Scenario scenario) throws Exception {
        String schema = "nx_e1_gate_it_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("NEXION_TEST_DB_SERVER_URL");
        var endpoint = java.net.URI.create(url.substring("jdbc:".length()));
        assertThat(endpoint.getHost()).isIn("127.0.0.1", "localhost", "::1", "[::1]");
        assertThat(endpoint.getPath()).isIn("", "/");
        var source = new DriverManagerDataSource(url, System.getenv("NEXION_TEST_DB_USERNAME"),
                System.getenv("NEXION_TEST_DB_PASSWORD"));
        var admin = new JdbcTemplate(source);
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        System.out.println("E1_GATE_SCHEMA_CREATED " + schema);
        try (var connection = source.getConnection()) {
            connection.setCatalog(schema);
            var dataSource = new SingleConnectionDataSource(connection, true);
            var config = new Configuration(new Environment("e1-gate-owned", new JdbcTransactionFactory(), dataSource));
            config.addMapper(DeviceCatalogMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var real = session.getMapper(DeviceCatalogMapper.class);
                var gateMethods = Set.of("createSkuTable", "countSkuPurchaseGateColumn", "addSkuPurchaseGateColumn", "seedGen2PurchaseGatesIfMissing");
                DeviceCatalogMapper scoped = mock(DeviceCatalogMapper.class, invocation ->
                        gateMethods.contains(invocation.getMethod().getName())
                                ? invocation.getMethod().invoke(real, invocation.getArguments())
                                : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
                scenario.run(new JdbcTemplate(dataSource), real, new MybatisDeviceCatalogRepository(scoped, new ObjectMapper()));
            }
        } finally {
            admin.execute("DROP DATABASE " + schema);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)).isZero();
            System.out.println("E1_GATE_SCHEMA_DROPPED " + schema);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(JdbcTemplate jdbc, DeviceCatalogMapper mapper, MybatisDeviceCatalogRepository repository) throws Exception;
    }
}
