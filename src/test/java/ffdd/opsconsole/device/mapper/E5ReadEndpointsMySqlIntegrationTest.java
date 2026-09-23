package ffdd.opsconsole.device.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceOpsRepository;
import ffdd.opsconsole.device.web.OpsDeviceController;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
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
                "jdbc:mysql://127.0.0.1:13307/?useSSL=false&allowPublicKeyRetrieval=true", "root", "")) {
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
                + "pending_deactivate TINYINT,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_user_device_runtime (user_device_id BIGINT PRIMARY KEY,online_status VARCHAR(32),"
                + "heartbeat_at DATETIME,active_task_no VARCHAR(96),gpu_usage DECIMAL(10,6),"
                + "gpu_temp_c DECIMAL(10,6),gpu_power_w DECIMAL(18,6),updated_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_datacenter (dc_location VARCHAR(128) PRIMARY KEY,region_label VARCHAR(128),"
                + "location VARCHAR(128),display_name VARCHAR(128),status VARCHAR(24),sort_order INT,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_compute_dc_ops_state (dc_location VARCHAR(128) PRIMARY KEY,dispatch_paused TINYINT,"
                + "paused_reason VARCHAR(160),paused_at DATETIME,resumed_at DATETIME,is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_event_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY,event_type VARCHAR(96),"
                + "aggregate_type VARCHAR(96),aggregate_id VARCHAR(96),created_at DATETIME,is_deleted TINYINT)");
    }
}
