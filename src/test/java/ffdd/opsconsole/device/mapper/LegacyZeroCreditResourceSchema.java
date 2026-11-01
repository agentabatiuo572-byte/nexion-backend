package ffdd.opsconsole.device.mapper;

import org.springframework.jdbc.core.JdbcTemplate;

/** Columns read by the production resource predicates, in a disposable schema only. */
public final class LegacyZeroCreditResourceSchema {
    private LegacyZeroCreditResourceSchema() { }
    public static void requests(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE nx_test_cloud_request(id BIGINT AUTO_INCREMENT PRIMARY KEY,device_id BIGINT,owner_id BIGINT,
              state VARCHAR(16),executor_id VARCHAR(32),operation VARCHAR(8) DEFAULT 'COMPLETE',
              key_hash CHAR(64),body_hash CHAR(64),instance_no VARCHAR(128),task_config_id VARCHAR(64),
              task_no VARCHAR(96),generation BIGINT DEFAULT 1,recovery_used TINYINT DEFAULT 0,updated_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6))
            """);
    }
    public static void resolutions(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE nx_test_cloud_zero_credit_resolution(request_id BIGINT,executor_id VARCHAR(32),operation VARCHAR(8),
              request_key_hash CHAR(64),request_body_hash CHAR(64),owner_id BIGINT,device_id BIGINT,instance_no VARCHAR(128),
              task_config_id VARCHAR(64),task_no VARCHAR(96),request_generation BIGINT,deployment_scope VARCHAR(16),
              incident_case_id VARCHAR(96),outcome VARCHAR(32),task_status VARCHAR(16),case_set_sha256 CHAR(64),
              UNIQUE KEY uk_request(request_id,request_generation))
            """);
    }
}
