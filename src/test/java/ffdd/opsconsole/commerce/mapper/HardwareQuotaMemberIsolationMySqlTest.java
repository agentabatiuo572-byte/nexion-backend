package ffdd.opsconsole.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual MyBatis queries in a private disposable schema; never writes business tables. */
@EnabledIfEnvironmentVariable(named = "NEXION_HARDWARE_QUOTA_IT", matches = "true")
class HardwareQuotaMemberIsolationMySqlTest {
    @Test
    void storefrontAndPaymentExcludeInvalidDirectMembersAndTheirSubtrees() throws Exception {
        String endpoint = System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT");
        assertThat(endpoint).as("explicit local test-server endpoint").matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}");
        assertThat(endpoint).as("business MySQL port is excluded").doesNotEndWith(":3306");
        String schema = "nexion_quota_it_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("nexion_quota_it_[a-f0-9]{32}");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String base = "jdbc:mysql://" + endpoint + "/";
        String password = System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", "");
        try (Connection admin = DriverManager.getConnection(base + options, "root", password)) {
            try (var ddl = admin.createStatement()) { ddl.execute("CREATE DATABASE " + schema); }
            try (Connection connection = DriverManager.getConnection(base + schema + options, "root", password)) {
                seed(connection);
                Configuration configuration = new Configuration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.addMapper(AppOrderCommandMapper.class);
                configuration.addMapper(CanonicalStateMapper.class);
                configuration.addMapper(ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.class);
                configuration.addMapper(ffdd.opsconsole.device.mapper.AppTradeinMapper.class);
                try (var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(connection)) {
                    var payment = session.getMapper(AppOrderCommandMapper.class);
                    var storefront = session.getMapper(CanonicalStateMapper.class);
                    // Valid direct 2 and descendant 20 contribute 100 + 25. Invalid roots and
                    // their otherwise valid descendants must contribute nothing.
                    assertThat(payment.purchaseQuotaFacts(1L).monthlyVolumeUsd()).isEqualByComparingTo("125");
                    assertThat(payment.purchaseQuotaFacts(1L).activeDirect()).isEqualTo(1L);
                    assertThat(storefront.purchaseQuotaMonthlyVolume(1L)).isEqualByComparingTo("125");
                    assertThat(payment.purchaseQuotaFacts(9L).monthlyVolumeUsd()).isEqualByComparingTo("40");
                    assertThat(storefront.purchaseQuotaMonthlyVolume(9L)).isEqualByComparingTo("40");
                    assertThat(payment.purchaseQuotaFacts(99L).monthlyVolumeUsd()).isEqualByComparingTo("0");
                    assertThat(storefront.purchaseQuotaMonthlyVolume(99L)).isEqualByComparingTo("0");
                    var quota = storefront.purchaseHardwareQuotas("stellarbox-pro").get(0);
                    assertThat(quota.quotaCode()).isEqualTo("PRO");
                    assertThat(quota.directRefs()).isEqualTo(2);
                    assertThat(quota.monthVolumeUsd()).isEqualByComparingTo("1000");
                    assertThat(quota.monthlyQuota()).isEqualTo(10);
                    assertThat(quota.usedThisMonth()).isEqualTo(3L);
                    assertThat(quota.unlockMode()).isEqualTo("ALL");
                    assertThat(quota.status()).isEqualTo(1);
                    List<ffdd.opsconsole.shared.canonical.mapper.HardwareQuotaPurchaseMapper> settlementMappers = List.of(
                            session.getMapper(ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.class),
                            session.getMapper(ffdd.opsconsole.device.mapper.AppTradeinMapper.class));
                    for (var settlementMapper : settlementMappers) {
                        assertThat(settlementMapper.hardwarePurchaseFacts(1L).monthlyVolumeUsd()).isEqualByComparingTo("125");
                        assertThat(settlementMapper.hardwarePurchaseFacts(1L).activeDirect()).isEqualTo(1L);
                        assertThat(settlementMapper.lockHardwarePurchaseTiers("stellarbox-pro")).hasSize(1);
                        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard.reserve(settlementMapper,
                                        1L, "stellarbox-pro", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))
                                .hasMessage("ORDER_MONTHLY_QUOTA_REQUIREMENTS_NOT_MET");
                    }
                    try (var update = connection.createStatement()) {
                        update.execute("UPDATE nx_team_hardware_quota_tier SET direct_refs=0,month_volume_usd=0");
                    }
                    session.clearCache();
                    for (var settlementMapper : settlementMappers) {
                        var reservation = ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard.reserve(
                                settlementMapper, 1L, "stellarbox-pro", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
                        ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard.record(
                                settlementMapper, reservation, 1L, "TEST-" + UUID.randomUUID());
                    }
                    session.clearCache();
                    assertThat(storefront.purchaseHardwareQuotas("stellarbox-pro").get(0).usedThisMonth()).isEqualTo(5L);
                    try (var update = connection.createStatement()) {
                        update.execute("INSERT INTO nx_team_hardware_quota_tier VALUES (2,'stellarbox-pro','PRO_SECOND',0,0,0,'EITHER',1,0)");
                    }
                    session.clearCache();
                    assertThat(storefront.purchaseHardwareQuotas("stellarbox-pro"))
                            .extracting(CanonicalStateMapper.HardwareQuota::quotaCode).containsExactly("PRO", "PRO_SECOND");
                    for (var settlementMapper : settlementMappers) {
                        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard.reserve(settlementMapper,
                                        1L, "stellarbox-pro", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))
                                .hasMessage("ORDER_MONTHLY_QUOTA_EXHAUSTED");
                    }
                }
            } finally {
                try (var ddl = admin.createStatement()) { ddl.execute("DROP DATABASE " + schema); }
            }
        }
    }

    private static void seed(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nx_user (id BIGINT PRIMARY KEY,sponsor_user_id BIGINT,sandbox INT,status VARCHAR(32),is_deleted INT)");
            sql.execute("CREATE TABLE nx_team_member (user_id BIGINT,member_user_id BIGINT,level INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_order (user_id BIGINT,subtotal_usdt DECIMAL(20,6),payment_status VARCHAR(32),order_status VARCHAR(32),paid_at DATETIME,created_at DATETIME,is_deleted INT)");
            sql.execute("CREATE TABLE nx_team_hardware_quota_tier (id BIGINT,product_no VARCHAR(64),quota_code VARCHAR(32),direct_refs INT,month_volume_usd DECIMAL(20,6),monthly_quota INT,unlock_mode VARCHAR(32),status INT,is_deleted INT)");
            sql.execute("CREATE TABLE nx_team_hardware_quota_usage (id BIGINT AUTO_INCREMENT PRIMARY KEY,quota_tier_id BIGINT,quantity INT,status VARCHAR(32),occurred_at DATETIME,is_deleted INT DEFAULT 0,quota_code VARCHAR(32),product_no VARCHAR(64),user_id BIGINT,order_no VARCHAR(64),usage_type VARCHAR(16),remark VARCHAR(128))");
            sql.execute("INSERT INTO nx_team_hardware_quota_tier VALUES (1,'stellarbox-pro','PRO',2,1000,10,'ALL',1,0)");
            sql.execute("INSERT INTO nx_team_hardware_quota_usage (quota_tier_id,quantity,status,occurred_at,is_deleted) VALUES (1,3,'ACTIVE',UTC_TIMESTAMP(),0),(1,8,'CANCELLED',UTC_TIMESTAMP(),0),(1,6,'ACTIVE',UTC_TIMESTAMP(),1),(1,7,'ACTIVE',DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-01'),INTERVAL 1 DAY),0)");
            sql.execute("INSERT INTO nx_user VALUES (1,NULL,0,'ACTIVE',0),(2,1,0,'ACTIVE',0),(3,1,1,'ACTIVE',0),(4,1,0,'LOCKED',0),(5,1,0,'ACTIVE',1),(6,1,0,'ACTIVE',0),(9,NULL,1,'ACTIVE',0),(10,9,1,'ACTIVE',0),(20,2,0,'ACTIVE',0),(30,3,0,'ACTIVE',0),(40,4,0,'ACTIVE',0),(50,5,0,'ACTIVE',0)");
            // A deleted relationship is also excluded. Its user is not a direct sponsor.
            sql.execute("UPDATE nx_user SET sponsor_user_id=NULL WHERE id=6");
            sql.execute("INSERT INTO nx_team_member VALUES (1,2,1,0),(1,3,1,0),(1,4,1,0),(1,5,1,0),(1,6,1,1),(2,20,1,0),(3,30,1,0),(4,40,1,0),(5,50,1,0),(9,10,1,0)");
            String month = "DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-01'),INTERVAL 9 HOUR)";
            for (int member : new int[]{2,3,4,5,6,10,20,30,40,50}) {
                int amount = member == 2 ? 100 : member == 20 ? 25 : member == 10 ? 40 : 1000;
                sql.execute("INSERT INTO nx_order VALUES (" + member + "," + amount + ",'PAID','COMPLETED'," + month + "," + month + ",0)");
            }
            sql.execute("INSERT INTO nx_order VALUES (2,1000,'PAID','REFUNDED'," + month + "," + month + ",0),(2,1000,'PENDING','COMPLETED'," + month + "," + month + ",0),(2,1000,'PAID','COMPLETED'," + month + "," + month + ",1)");
        }
    }
}
