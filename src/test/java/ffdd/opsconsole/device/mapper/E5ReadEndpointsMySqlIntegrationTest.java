package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceOpsRepository;
import ffdd.opsconsole.device.web.OpsDeviceController;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** #44: exercise the three real E5 read paths against an isolated MySQL schema. */
@EnabledIfEnvironmentVariable(named = "NEXION_E5_MYSQL_IT", matches = "true")
class E5ReadEndpointsMySqlIntegrationTest {
    @Test
    void emptyAndPopulatedFleetReadsDoNotFail() throws Exception {
        String database = "nx_e5_read_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_e5_read_[a-f0-9]{32}");
        try (Connection connection = DriverManager.getConnection(
                isolatedUrl(), "root", "")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                jdbc.execute("USE `" + database + "`");
                createTables(jdbc);
                DeviceOpsMapper mapper = mapper(connection);
                MybatisDeviceOpsRepository repository = new MybatisDeviceOpsRepository(
                        mapper, mock(OpsReadTimeSeedPolicy.class));
                OpsDeviceService service = mock(OpsDeviceService.class, CALLS_REAL_METHODS);
                PlatformConfigFacade config = mock(PlatformConfigFacade.class);
                when(config.activeValue("device.max_active_slots")).thenReturn(Optional.empty());
                ReflectionTestUtils.setField(service, "deviceRepository", repository);
                ReflectionTestUtils.setField(service, "configFacade", config);
                ReflectionTestUtils.setField(service, "clock", Clock.systemUTC());
                OpsDeviceController controller = new OpsDeviceController(service);

                assertThat(controller.observability().getData()).containsKey("telemetry");
                assertThat(controller.overview().getData()).containsEntry("totalDevices", 0L);
                assertThat(controller.datacenters().getData()).isEmpty();

                jdbc.execute("INSERT INTO nx_compute_datacenter (dc_location,region_label,location,display_name,status,sort_order,is_deleted) "
                        + "VALUES ('DC-1','Asia','Tokyo','Tokyo DC','active',1,0)");
                jdbc.execute("INSERT INTO nx_user_device (id,dc_location,status,ownership_status,activated_at,pending_deactivate,is_deleted) "
                        + "VALUES (7,'DC-1','ONLINE','OWNED',NOW(),0,0)");
                jdbc.execute("INSERT INTO nx_user_device_runtime (user_device_id,online_status,heartbeat_at,gpu_usage,gpu_temp_c,gpu_power_w,updated_at,is_deleted) "
                        + "VALUES (7,'ONLINE',NOW(),51.2,66.1,208.5,NOW(),0)");
                jdbc.execute("INSERT INTO nx_event_outbox (event_type,aggregate_type,aggregate_id,created_at,is_deleted) "
                        + "VALUES ('admin.device_activated','E5_DEVICE','7',NOW(),0)");

                Map<String, Object> telemetry = (Map<String, Object>) controller.observability().getData().get("telemetry");
                assertThat(telemetry).containsEntry("heartbeatLost1h", 0L);
                assertThat((List<?>) controller.observability().getData().get("activity")).hasSize(1);
                assertThat(controller.overview().getData()).containsEntry("totalDevices", 1L)
                        .containsEntry("onlineDevices", 1L);
                List<DeviceDatacenterView> datacenters = controller.datacenters().getData();
                assertThat(datacenters).hasSize(1);
                assertThat(datacenters.get(0).dcLocation()).isEqualTo("DC-1");
                assertThat(datacenters.get(0).totalDevices()).isEqualTo(1L);
            } finally {
                jdbc.execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    @Test
    void effectiveRuntimeMatchesOverviewFiltersDatacentersAndGlobeAtHeartbeatBoundaries() throws Exception {
        String database = "nx_e5_read_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_e5_read_[a-f0-9]{32}");
        try (Connection connection = DriverManager.getConnection(isolatedUrl(), "root", "")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                jdbc.execute("USE `" + database + "`");
                jdbc.execute("SET time_zone = '+00:00'");
                jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2026-09-30 12:00:00')");
                createTables(jdbc);
                jdbc.execute("INSERT INTO nx_compute_datacenter (dc_location,region_label,location,display_name,status,sort_order,is_deleted) "
                        + "VALUES ('DC-1','Asia','Tokyo','Tokyo DC','active',1,0)");
                jdbc.execute("INSERT INTO nx_user VALUES (7,'viewer','ACTIVE',0,0),(8,'sandbox','ACTIVE',1,0)");
                device(jdbc, 1, "ACTIVE", "ONLINE", "2026-09-30 11:50:00");
                device(jdbc, 2, "BUSY", "ONLINE", "2026-09-30 11:49:59.999999");
                device(jdbc, 3, "ACTIVE", "ONLINE", null);
                device(jdbc, 4, "ACTIVE", "ONLINE", "2026-09-30 12:00:00.001");
                device(jdbc, 5, "OFFLINE", "OFFLINE", "2026-09-30 12:00:00");
                device(jdbc, 6, "ACTIVE", "ERROR", "2026-09-30 12:00:00");
                device(jdbc, 7, "ACTIVE", "UNKNOWN", "2026-09-30 12:00:00");
                device(jdbc, 8, "ACTIVE", "ONLINE", "2026-09-30 10:59:59");
                device(jdbc, 9, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET device_type='SHARE' WHERE id=9");
                device(jdbc, 10, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=10");
                device(jdbc, 11, "INVENTORY", "ONLINE", "2026-09-30 12:00:00");
                device(jdbc, 12, "DEACTIVATED", "ONLINE", "2026-09-30 12:00:00");
                device(jdbc, 13, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET activated_at=NULL WHERE id=13");
                device(jdbc, 14, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET deactivated_at=NOW() WHERE id=14");
                device(jdbc, 16, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device_runtime SET is_deleted=1 WHERE user_device_id=16");
                device(jdbc, 17, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET is_deleted=1 WHERE id=17");
                device(jdbc, 18, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET user_id=8 WHERE id=18");

                DeviceOpsMapper mapper = mapper(connection);
                assertStateIds(mapper, "ACTIVE", 1L, 9L, 18L);
                assertStateIds(mapper, "ONLINE", 1L, 9L, 18L);
                assertStateIds(mapper, "BUSY");
                assertStateIds(mapper, "OFFLINE", 2L, 3L, 4L, 5L, 8L);
                assertStateIds(mapper, "ABNORMAL", 6L);
                assertStateIds(mapper, "UNKNOWN", 7L, 13L, 14L, 16L);
                assertStateIds(mapper, "INVENTORY", 11L);
                assertStateIds(mapper, "UNBOUND", 12L);
                assertStateIds(mapper, "PENDING-DEACTIVATE", 10L);
                assertHeartbeatIds(mapper, "ONLINE", "fresh", 1L, 9L, 18L);
                assertHeartbeatIds(mapper, "ONLINE", "stale");
                assertHeartbeatIds(mapper, "OFFLINE", "fresh", 5L);
                assertHeartbeatIds(mapper, "OFFLINE", "stale", 2L, 3L, 4L, 8L);
                assertHeartbeatIds(mapper, "OFFLINE", "missing", 3L);
                assertThat(mapper.countOnlineDevices()).isEqualTo(3);
                assertThat(mapper.countOfflineDevices()).isEqualTo(5);
                assertThat(mapper.countAbnormalDevices()).isEqualTo(6);
                assertThat(mapper.e5FleetObservabilityMetrics().heartbeatLost1h()).isEqualTo(3);
                assertThat(mapper.e5FleetObservabilityMetrics().persistentOffline1h()).isEqualTo(2);
                assertThat(mapper.datacenterSummaries().get(0).onlineDevices()).isEqualTo(3);
                assertThat(mapper.datacenterSummaries().get(0).abnormalDevices()).isEqualTo(6);
                assertThat(mapper.findDatacenter("DC-1").onlineDevices()).isEqualTo(3);
                assertThat(mapper.findDatacenter("DC-1").abnormalDevices()).isEqualTo(6);
                assertThat(mapper.findDevice(1L).heartbeatAgeSeconds()).isEqualByComparingTo("600");
                assertThat(mapper.findDevice(2L).heartbeatAgeSeconds()).isEqualByComparingTo("600.000001");
                assertThat(mapper.findDevice(3L).heartbeatAgeSeconds()).isNull();
                assertThat(mapper.findDevice(4L).heartbeatAgeSeconds()).isNull();
                assertThat(mapper.findDevice(5L).heartbeatAgeSeconds()).isEqualByComparingTo("0");
                assertThat(mapper.findDevice(2L).runtimeStatus()).isEqualTo("OFFLINE");
                assertThat(mapper.findDevice(3L).runtimeStatus()).isEqualTo("OFFLINE");
                assertThat(mapper.findDevice(4L).runtimeStatus()).isEqualTo("OFFLINE");
                assertThat(mapper.findDevice(2L).status()).isEqualTo("BUSY");
                assertThat(mapper.findDevice(2L).activeTaskNo()).isEqualTo("task-2");
                assertThat(mapper.countActiveDevicesByUser(7L)).isEqualTo(9);
                assertThat(mapper.findDevice(2L).activeDevicesForUser()).isEqualTo(9);
                assertThat(mapper.findDevice(2L).userDeviceSlotNo()).isEqualTo(2);
                assertThat(mapper.listUserDevices(7L, 100L).stream().filter(d -> d.id() == 2L).findFirst().orElseThrow()
                        .runtimeStatus()).isEqualTo("OFFLINE");
                AppNetworkRegionMapper globe = regionMapper(connection);
                assertThat(globe.regions(7L).get(0).activeNodes()).isEqualTo(2);

                // A new genuine heartbeat restores visibility, without changing lifecycle or capacity.
                jdbc.execute("UPDATE nx_user_device_runtime SET heartbeat_at=NOW() WHERE user_device_id IN (2,8)");
                assertStateIds(mapper, "BUSY", 2L);
                assertStateIds(mapper, "ONLINE", 1L, 2L, 8L, 9L, 18L);
                assertStateIds(mapper, "OFFLINE", 3L, 4L, 5L);
                assertThat(mapper.countOnlineDevices()).isEqualTo(5);
                assertThat(mapper.countAbnormalDevices()).isEqualTo(4);
                assertThat(mapper.e5FleetObservabilityMetrics().persistentOffline1h()).isEqualTo(1);
                assertThat(mapper.datacenterSummaries().get(0).onlineDevices()).isEqualTo(5);
                assertThat(globe.regions(7L).get(0).activeNodes()).isEqualTo(4);
                assertThat(mapper.countActiveDevicesByUser(7L)).isEqualTo(9);
                assertThat(mapper.findDevice(2L).status()).isEqualTo("BUSY");
                assertThat(mapper.findDevice(2L).activeTaskNo()).isEqualTo("task-2");
            } finally {
                jdbc.execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    private static String isolatedUrl() {
        int port = Integer.parseInt(System.getenv().getOrDefault("NEXION_E5_MYSQL_PORT", "13307"));
        assertThat(port).isBetween(1024, 65535).isNotEqualTo(3306);
        return "jdbc:mysql://127.0.0.1:" + port + "/?useSSL=false&allowPublicKeyRetrieval=true";
    }

    @Test
    void appFleetUsesTheE5HeartbeatWindowWithoutChangingLifecycleOrEarnings() throws Exception {
        String database = "nx_e5_read_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_e5_read_[a-f0-9]{32}");
        try (Connection connection = DriverManager.getConnection(isolatedUrl(), "root", "")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(
                    Integer.parseInt(System.getenv().getOrDefault("NEXION_E5_MYSQL_PORT", "13307")));
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isNull();
            jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                jdbc.execute("USE `" + database + "`");
                assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
                jdbc.execute("SET time_zone = '+00:00'");
                jdbc.execute("SET timestamp = UNIX_TIMESTAMP('2026-09-30 12:00:00')");
                createTables(jdbc);
                jdbc.execute("INSERT INTO nx_user VALUES (7,'viewer','ACTIVE',0,0),(8,'other','ACTIVE',0,0)");
                device(jdbc, 1, "ACTIVE", "ONLINE", "2026-09-30 11:50:00");
                device(jdbc, 2, "BUSY", "ONLINE", "2026-09-30 11:49:59.999999");
                device(jdbc, 3, "ACTIVE", "ONLINE", null);
                device(jdbc, 4, "ACTIVE", "ONLINE", "2026-09-30 12:00:00.000001");
                device(jdbc, 5, "OFFLINE", "OFFLINE", "2026-09-30 12:00:00");
                device(jdbc, 6, "ACTIVE", "ERROR", "2026-09-30 12:00:00");
                device(jdbc, 7, "ACTIVE", "UNKNOWN", null);
                device(jdbc, 8, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device_runtime SET is_deleted=1 WHERE user_device_id=8");
                device(jdbc, 9, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET pending_deactivate=1 WHERE id=9");
                device(jdbc, 10, "DEACTIVATED", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET deactivated_at=NOW() WHERE id=10");
                device(jdbc, 11, "INVENTORY", " online ", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET activated_at=NULL WHERE id=11");
                device(jdbc, 12, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET is_deleted=1 WHERE id=12");
                device(jdbc, 13, "ACTIVE", "ONLINE", "2026-09-30 12:00:00");
                jdbc.execute("UPDATE nx_user_device SET user_id=8 WHERE id=13");
                jdbc.execute("UPDATE nx_user_device SET device_type='PHONE',row_version=7,"
                        + "daily_usdt=2.123456,daily_nex=3.654321,hashrate=32.3,price_usdt_snapshot=9.5");
                jdbc.execute("INSERT INTO nx_compute_receipt VALUES (2,'PRODUCTION','POSTED',1.234567,0)");
                var before = jdbc.queryForList("SELECT d.id,d.status,d.row_version,d.activated_at,d.deactivated_at,"
                        + "d.pending_deactivate,r.online_status,r.heartbeat_at,r.active_task_no "
                        + "FROM nx_user_device d LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id ORDER BY d.id");
                var source = new SingleConnectionDataSource(connection, true);
                Configuration configuration = new Configuration(new Environment(
                        "app-fleet-read-test", new SpringManagedTransactionFactory(), source));
                configuration.addMapper(CanonicalStateMapper.class);
                CanonicalStateMapper app = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                        .getMapper(CanonicalStateMapper.class);
                var rows = app.ownedDevices(7L);
                assertThat(rows).extracting(CanonicalStateMapper.OwnedDevice::id)
                        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
                assertThat(rows).extracting(CanonicalStateMapper.OwnedDevice::runtimeStatus).containsExactly(
                        "ONLINE", "OFFLINE", "OFFLINE", "OFFLINE", "OFFLINE", "OFFLINE", "UNKNOWN", "UNKNOWN",
                        "ONLINE", "ONLINE", "ONLINE");
                DeviceOpsMapper e5 = mapper(connection);
                for (long id : List.of(1L, 2L, 3L, 4L, 5L)) {
                    assertThat(rows.get((int) id - 1).runtimeStatus()).as("App and E5 device %s", id)
                            .isEqualTo(e5.findDevice(id).runtimeStatus());
                }
                var stalePhone = rows.get(1);
                assertThat(stalePhone.status()).isEqualTo("BUSY");
                assertThat(stalePhone.rowVersion()).isEqualTo(7L);
                assertThat(stalePhone.dailyUsdt()).isEqualByComparingTo("2.123456");
                assertThat(stalePhone.dailyNex()).isEqualByComparingTo("3.654321");
                assertThat(stalePhone.actualPaidUsdt()).isEqualByComparingTo("9.5");
                assertThat(stalePhone.cumulativeOutputUsdt()).isEqualByComparingTo("1.234567");
                assertThat(stalePhone.activatedAt()).isNotNull();
                assertThat(rows.get(8).pendingDeactivate()).isTrue();
                assertThat(rows.get(9).deactivatedAt()).isNotNull();
                assertThat(rows.get(10).activatedAt()).isNull();
                assertThat(jdbc.queryForList("SELECT d.id,d.status,d.row_version,d.activated_at,d.deactivated_at,"
                        + "d.pending_deactivate,r.online_status,r.heartbeat_at,r.active_task_no "
                        + "FROM nx_user_device d LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id ORDER BY d.id"))
                        .isEqualTo(before);
                System.out.println("App fleet boundary states: " + rows.stream()
                        .map(row -> row.id() + "=" + row.runtimeStatus()).toList());
                jdbc.execute("UPDATE nx_user_device_runtime SET heartbeat_at=NOW(6) WHERE user_device_id=2");
                var restored = app.ownedDevices(7L).get(1);
                assertThat(restored.runtimeStatus()).isEqualTo("ONLINE");
                assertThat(restored.status()).isEqualTo("BUSY");
                assertThat(restored.cumulativeOutputUsdt()).isEqualByComparingTo("1.234567");
                assertThat(e5.findDevice(2L).activeTaskNo()).isEqualTo("task-2");
                assertThat(e5.findDevice(2L).heartbeatAgeSeconds()).isEqualByComparingTo("0");
                System.out.println("App fleet heartbeat recovery: 2=" + restored.runtimeStatus());
            } finally {
                jdbc.execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    private static void device(JdbcTemplate jdbc, long id, String lifecycle, String runtime, String heartbeat) {
        jdbc.update("INSERT INTO nx_user_device (id,user_id,instance_no,name,dc_location,status,ownership_status,"
                        + "activated_at,device_type,pending_deactivate,is_deleted,last_seen_at) "
                        + "VALUES (?,7,CONCAT('device-',?),'fixture','DC-1',?,'OWNED',NOW(),'DEVICE',0,0,NOW())",
                id, id, lifecycle);
        jdbc.update("INSERT INTO nx_user_device_runtime (user_device_id,online_status,heartbeat_at,active_task_no,is_deleted) "
                + "VALUES (?,?,?,CONCAT('task-',?),0)", id, runtime, heartbeat, id);
    }

    private static void assertStateIds(DeviceOpsMapper mapper, String status, Long... ids) {
        assertHeartbeatIds(mapper, status, null, ids);
    }

    private static void assertHeartbeatIds(DeviceOpsMapper mapper, String status, String heartbeat, Long... ids) {
        assertThat(mapper.countDevices(status, null, null, null, null, heartbeat)).isEqualTo(ids.length);
        assertThat(mapper.pageDevices(status, null, null, null, null, heartbeat, 100L, 0L))
                .extracting(DeviceOpsView::id).containsExactlyInAnyOrder(ids);
    }

    private static AppNetworkRegionMapper regionMapper(Connection connection) {
        var source = new SingleConnectionDataSource(connection, true);
        Configuration configuration = new Configuration(new Environment(
                "e5-globe-test", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(AppNetworkRegionMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(AppNetworkRegionMapper.class);
    }

    private static DeviceOpsMapper mapper(Connection connection) {
        var source = new SingleConnectionDataSource(connection, true);
        Configuration configuration = new Configuration(new Environment(
                "e5-read-test", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(DeviceOpsMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(DeviceOpsMapper.class);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE nx_user_device (id BIGINT PRIMARY KEY,dc_location VARCHAR(128),status VARCHAR(32),"
                + "ownership_status VARCHAR(32),activated_at DATETIME,deactivated_at DATETIME,"
                + "pending_deactivate TINYINT,is_deleted TINYINT,user_id BIGINT,instance_no VARCHAR(96),name VARCHAR(96),"
                + "product_tier VARCHAR(32),product_code VARCHAR(96),device_type VARCHAR(32),source_order_no VARCHAR(96),"
                + "hashrate DECIMAL(18,6),daily_usdt DECIMAL(18,6),daily_nex DECIMAL(18,6),last_seen_at DATETIME,"
                + "row_version BIGINT DEFAULT 0,product_id BIGINT,price_usdt_snapshot DECIMAL(18,6),gpu_model VARCHAR(96),"
                + "vram_total_gb INT,base_power_w DECIMAL(18,6),source_channel VARCHAR(96),"
                + "purchased_at DATETIME,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE nx_user_device_runtime (user_device_id BIGINT PRIMARY KEY,online_status VARCHAR(32),"
                + "heartbeat_at DATETIME(6),active_task_no VARCHAR(96),gpu_usage DECIMAL(10,6),"
                + "gpu_temp_c DECIMAL(10,6),gpu_power_w DECIMAL(18,6),updated_at DATETIME,is_deleted TINYINT,"
                + "paused_reason VARCHAR(96),battery_level INT,is_charging TINYINT,network_reachable TINYINT,thermal_state VARCHAR(32),"
                + "latitude DOUBLE,longitude DOUBLE)");
        jdbc.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,nickname VARCHAR(96),status VARCHAR(32),sandbox TINYINT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_product (id BIGINT PRIMARY KEY,price_usdt DECIMAL(18,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_order (order_no VARCHAR(96),user_id BIGINT,payment_status VARCHAR(32),"
                + "quantity INT,amount_usdt DECIMAL(18,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_receipt (user_device_id BIGINT,source_environment VARCHAR(32),"
                + "earning_status VARCHAR(32),reward_usdt DECIMAL(18,6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_admin_device_sku (sku_id VARCHAR(96),base_rate VARCHAR(96),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_tradein_application (source_device_id BIGINT,current_efficiency DECIMAL(18,6),created_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_task (user_device_id BIGINT,status VARCHAR(32),completed_at DATETIME,source_environment VARCHAR(32),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_datacenter (dc_location VARCHAR(128) PRIMARY KEY,region_label VARCHAR(128),"
                + "location VARCHAR(128),display_name VARCHAR(128),status VARCHAR(24),sort_order INT,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_dc_ops_state (dc_location VARCHAR(128) PRIMARY KEY,dispatch_paused TINYINT,"
                + "paused_reason VARCHAR(160),paused_at DATETIME,resumed_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_event_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_type VARCHAR(96),"
                + "aggregate_type VARCHAR(96),aggregate_id VARCHAR(96),created_at DATETIME,is_deleted TINYINT)");
    }
}
