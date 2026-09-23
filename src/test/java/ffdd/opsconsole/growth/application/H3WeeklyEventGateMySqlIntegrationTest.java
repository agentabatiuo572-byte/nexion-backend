package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Runs the actual migration twice against an owned MySQL 8 schema on loopback. */
class H3WeeklyEventGateMySqlIntegrationTest {
    private static final String SERVER = "jdbc:mysql://127.0.0.1:33317/";
    private static final Path MIGRATION = Path.of("scripts/migrations/20260923_h3_weekly_event_gate.sql");

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_H3_WEEKLY_GATE_IT", matches = "true")
    void quarantinesOnlyWrongMappingsAndRerunsWithoutChangingOperatorBindings() throws Exception {
        String configured = System.getenv("NEXION_H3_WEEKLY_GATE_MYSQL_URL");
        if (!SERVER.equals(configured)) throw new IllegalArgumentException("EXPLICIT_ISOLATED_33317_SERVER_REQUIRED");
        String schema = "nx_h3_weekly_gate_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(SERVER, "root", "")) {
            assertThat(admin.createStatement().executeQuery("SELECT @@port").next()).isTrue();
            admin.createStatement().execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            try {
                var source = new DriverManagerDataSource(SERVER + schema, "root", "");
                var jdbc = new JdbcTemplate(source);
                jdbc.execute("CREATE TABLE nx_admin_operation_mutex(lock_key VARCHAR(64) PRIMARY KEY,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
                jdbc.execute("CREATE TABLE nx_mission(id BIGINT AUTO_INCREMENT PRIMARY KEY,mission_code VARCHAR(64) NOT NULL UNIQUE,mission_type VARCHAR(32) NOT NULL,status TINYINT NOT NULL,is_deleted TINYINT NOT NULL DEFAULT 0,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
                jdbc.execute("CREATE TABLE nx_growth_quest_event_binding(id BIGINT AUTO_INCREMENT PRIMARY KEY,binding_code VARCHAR(48) NOT NULL UNIQUE,producer VARCHAR(32) NOT NULL,event_type VARCHAR(128) NOT NULL,quest_code VARCHAR(64) NOT NULL,user_id_field VARCHAR(64) NOT NULL,status TINYINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,is_deleted TINYINT NOT NULL DEFAULT 0,UNIQUE KEY uq_fact(producer,event_type,quest_code,user_id_field)) ENGINE=InnoDB");
                jdbc.update("INSERT INTO nx_mission(mission_code,mission_type,status) VALUES"
                        + "('weekly_t2_browse_store','WEEKLY_T2',1),"
                        + "('weekly_t2_invite_friend','WEEKLY_T2',1),"
                        + "('weekly_t2_nex_swap','WEEKLY_T2',1),"
                        + "('weekly_t2_ai_jobs_50','WEEKLY_T2',1),"
                        + "('weekly_t2_genesis_browse','WEEKLY_T2',1),"
                        + "('weekly_t1_unbound','WEEKLY_T1',1),"
                        + "('weekly_t1_buy_genesis','WEEKLY_T1',1)");
                jdbc.update("INSERT INTO nx_growth_quest_event_binding(binding_code,producer,event_type,quest_code,user_id_field,status) VALUES"
                        + "('WEEKLY_INVITE_REGISTERED','SYSTEM','H3_STOREFRONT_THREE_PRODUCTS_VIEWED','weekly_t2_browse_store','user_id',1),"
                        + "('WRONG_COMPUTE','SYSTEM','H3_COMPUTE_COMPLETED_50','weekly_t2_invite_friend','user_id',1),"
                        + "('WEEKLY_NEX_EXCHANGED','OPERATOR','unrelated','operator_task','account_id',0)");

                // Mirror the failed attempt: its three CREATE TABLE statements persisted.
                String ddl = Files.readString(MIGRATION).split("START TRANSACTION;", 2)[0];
                try (Connection connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
                }
                run(source);
                assertThat(jdbc.queryForObject("SELECT status FROM nx_growth_quest_event_binding WHERE binding_code='WEEKLY_INVITE_REGISTERED'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM nx_growth_quest_event_binding WHERE binding_code='WRONG_COMPUTE'", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT status FROM nx_growth_quest_event_binding WHERE binding_code='WEEKLY_NEX_EXCHANGED'", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_binding_quarantine_receipt", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding WHERE status=1 AND quest_code='weekly_t2_invite_friend' AND event_type='H3_REFERRAL_REGISTERED'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_growth_quest_event_binding WHERE status=1 AND quest_code='weekly_t2_nex_swap' AND event_type='H3_EXCHANGE_COMPLETED'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM nx_mission WHERE mission_code='weekly_t1_unbound'", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT status FROM nx_mission WHERE mission_code='weekly_t1_buy_genesis'", Integer.class)).isZero();
                var bindings = jdbc.queryForList("SELECT binding_code,producer,event_type,quest_code,user_id_field,status,is_deleted FROM nx_growth_quest_event_binding ORDER BY id");
                var pauses = jdbc.queryForList("SELECT mission_code,reason,previous_status FROM nx_growth_mission_gate_pause_receipt ORDER BY id");
                var rollouts = jdbc.queryForList("SELECT mission_code,first_applied_at FROM nx_growth_mission_business_gate_rollout ORDER BY mission_code");

                run(source);
                assertThat(jdbc.queryForList("SELECT binding_code,producer,event_type,quest_code,user_id_field,status,is_deleted FROM nx_growth_quest_event_binding ORDER BY id")).isEqualTo(bindings);
                assertThat(jdbc.queryForList("SELECT mission_code,reason,previous_status FROM nx_growth_mission_gate_pause_receipt ORDER BY id")).isEqualTo(pauses);
                assertThat(jdbc.queryForList("SELECT mission_code,first_applied_at FROM nx_growth_mission_business_gate_rollout ORDER BY mission_code")).isEqualTo(rollouts);
            } finally {
                admin.createStatement().execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    private static void run(DriverManagerDataSource source) throws Exception {
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
        }
    }
}
