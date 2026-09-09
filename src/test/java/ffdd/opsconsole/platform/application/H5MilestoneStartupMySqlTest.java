package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
class H5MilestoneStartupMySqlTest {
    @Test
    void repeatedStartupPreservesPcConfigurationAndConvertsNonDeletedLegacyRewards() throws Exception {
        String database = "nx_h5_seed_test_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("nx_h5_seed_test_[0-9a-f]{32}");
        boolean fixtureCreated = false;
        try (var connection = DriverManager.getConnection(
                System.getenv().getOrDefault("NEXION_TEST_DB_URL",
                        "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"),
                System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root"),
                System.getenv("NEXION_TEST_DB_PASSWORD"))) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            fixtureCreated = true;
            try {
                jdbc.execute("USE `" + database + "`");
                assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
                jdbc.execute("""
                        CREATE TABLE nx_streak_milestone (
                          milestone_day INT PRIMARY KEY, milestone_name VARCHAR(128), reward_type VARCHAR(32),
                          reward_amount DECIMAL(18,6), reward_name VARCHAR(128), badge_achievement_code VARCHAR(64),
                          sort_order INT, status INT, is_deleted INT, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                String source = Files.readString(Path.of("scripts/migrations/20260722_h_domain_closure.sql"));
                String seed = source.substring(source.indexOf("INSERT INTO nx_streak_milestone"),
                        source.indexOf("INSERT INTO nx_streak_power_up")).trim();
                jdbc.execute(seed);
                assertThat(jdbc.queryForList("SELECT reward_type FROM nx_streak_milestone WHERE milestone_day IN (3,7)", String.class))
                        .containsOnly("NEX");
                jdbc.update("UPDATE nx_streak_milestone SET reward_type='POINTS',reward_amount=5.125,reward_name='legacy',status=0 WHERE milestone_day=3");
                jdbc.update("UPDATE nx_streak_milestone SET reward_type='USDT',reward_amount=2.75,reward_name='PC custom',status=0 WHERE milestone_day=7");
                jdbc.update("UPDATE nx_streak_milestone SET is_deleted=1,status=0,reward_amount=42 WHERE milestone_day=14");
                for (int run = 0; run < 2; run++) {
                    jdbc.execute(seed);
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                            "scripts/migrations/20260909_h5_legacy_milestone_reward.sql"));
                }
                assertThat(jdbc.queryForMap("SELECT reward_type,reward_name,status FROM nx_streak_milestone WHERE milestone_day=3"))
                        .containsEntry("reward_type", "NEX").containsEntry("reward_name", "+5.125 NEX").containsEntry("status", 0);
                assertThat(jdbc.queryForMap("SELECT reward_type,reward_name,status FROM nx_streak_milestone WHERE milestone_day=7"))
                        .containsEntry("reward_type", "USDT").containsEntry("reward_name", "PC custom").containsEntry("status", 0);
                assertThat(jdbc.queryForObject("SELECT reward_amount FROM nx_streak_milestone WHERE milestone_day=7", java.math.BigDecimal.class))
                        .isEqualByComparingTo("2.75");
                assertThat(jdbc.queryForMap("SELECT status,is_deleted FROM nx_streak_milestone WHERE milestone_day=14"))
                        .containsEntry("status", 0).containsEntry("is_deleted", 1);
                jdbc.execute("""
                        CREATE TABLE nx_streak_power_up (
                          power_up_code VARCHAR(64) PRIMARY KEY, power_up_name VARCHAR(128), i18n_key VARCHAR(128),
                          target_path VARCHAR(128), badge_achievement_code VARCHAR(64), unlock_streak_days INT,
                          effect_type VARCHAR(64), effect_value VARCHAR(255), duration_days INT, sort_order INT,
                          status INT, is_deleted INT
                        )
                        """);
                String powerSeed = source.substring(source.indexOf("INSERT INTO nx_streak_power_up"),
                        source.indexOf("UPDATE nx_streak_power_up")).trim();
                jdbc.execute(powerSeed);
                jdbc.update("UPDATE nx_streak_power_up SET unlock_streak_days=9,effect_value='PC configured note',status=0,is_deleted=1 WHERE power_up_code='ROYALTY_BOOST'");
                jdbc.update("UPDATE nx_streak_power_up SET unlock_streak_days=37,effect_type='LEGACY_APY',effect_value='retired financial promise' WHERE power_up_code='STAKING_APY'");
                jdbc.execute(powerSeed);
                jdbc.execute(powerSeed);
                assertThat(jdbc.queryForMap("SELECT unlock_streak_days,effect_value,status,is_deleted FROM nx_streak_power_up WHERE power_up_code='ROYALTY_BOOST'"))
                        .containsEntry("unlock_streak_days", 9).containsEntry("effect_value", "PC configured note")
                        .containsEntry("status", 0).containsEntry("is_deleted", 1);
                assertThat(jdbc.queryForMap("SELECT unlock_streak_days,effect_type FROM nx_streak_power_up WHERE power_up_code='STAKING_APY'"))
                        .containsEntry("unlock_streak_days", 37).containsEntry("effect_type", "ROUTE_BADGE");
                assertThat(jdbc.queryForObject("SELECT effect_value FROM nx_streak_power_up WHERE power_up_code='STAKING_APY'", String.class))
                        .doesNotContain("retired financial promise");
            } finally {
                if (fixtureCreated) {
                    assertThat(database).matches("nx_h5_seed_test_[0-9a-f]{32}");
                    jdbc.execute("USE information_schema");
                    jdbc.execute("DROP DATABASE `" + database + "`");
                }
            }
        }
    }
}
