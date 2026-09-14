package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.finance.application.AppVietQrIntentService;
import ffdd.opsconsole.finance.application.FinanceSensitiveDataCipher;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Production SQL and transaction boundaries, with a fake gateway and a disposable UUID schema only. */
class HdPayHostedRailMySqlTest {
    private static final String PREFIX = "nexion_hosted_rail_it_";
    private static final String MIGRATION = "20260907_hdpay_optional_manual_bank.sql";

    @Test
    void connectionGuardRejectsBusinessEndpointsAndUnownedSchemas() {
        String schema = PREFIX + "a".repeat(32);
        assertThat(url("127.0.0.1:13306", schema)).contains(":13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306", "127.0.0.1:13306/other"}) {
            assertThatThrownBy(() -> url(endpoint, schema)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalid : new String[]{null, "nexion", "mysql", PREFIX + "a", schema + "`"}) {
            assertThatThrownBy(() -> url("127.0.0.1:13306", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void manualOnlyMigrationIsRepeatableWithoutAnyProviderTablesAndPreservesHistory() throws Exception {
        inSchema(f -> {
            f.base();
            f.legacyIntent("VQR-MANUAL001", "manual-key");
            Map<String, Object> before = f.jdbc.queryForMap("SELECT requested_usdt,payable_vnd,bank_account_id,status FROM nx_vietqr_intent");
            assertThat(f.orders.countNullableIntentBankAccountColumn()).isZero();
            f.migrate(MIGRATION); f.migrate(MIGRATION);
            assertThat(f.orders.countNullableIntentBankAccountColumn()).isEqualTo(1);
            assertThat(f.orders.countIntentPaymentRailColumn()).isEqualTo(1);
            assertThat(f.jdbc.queryForMap("SELECT requested_usdt,payable_vnd,bank_account_id,status FROM nx_vietqr_intent")).isEqualTo(before);
            assertThat(f.jdbc.queryForObject("SELECT payment_rail FROM nx_vietqr_intent", String.class)).isEqualTo("MANUAL");
            assertThat(f.orders.countRequiredSchemaTables()).isZero();
            assertThat(f.intents.findIntentByCreateKey(41L, "manual-key")).containsEntry("paymentRail", "MANUAL");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void newHostedOrderUsesNoBankAndCrossRailRetriesCannotSubmitOrCancelIt() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.migrate(MIGRATION);
            var properties = properties();
            new HdPaySchemaReadiness(properties, f.orders).verify();
            AppVietQrIntentService canonical = f.service();
            HdPayGateway gateway = mock(HdPayGateway.class);
            when(gateway.createPayOrder(any())).thenReturn(new HdPayGateway.PayPage("https://api.hdpayadmin.com/pay?id=isolated"));
            HdPayHostedDepositService hosted = new HdPayHostedDepositService(canonical, properties, gateway, f.orders);
            Map<?, ?> config = (Map<?, ?>) hosted.paymentConfig().getData().get("vietQr");
            assertThat(config.get("enabled")).isEqualTo(true);
            assertThat(config.get("dailyCapacityKnown")).isEqualTo(false);
            var first = hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData();
            var repeated = hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData();
            String intentNo = (String) first.get("intentNo");
            assertThat(repeated).containsEntry("intentNo", intentNo).containsEntry("providerStatus", "created");
            assertThat(first).doesNotContainKeys("bankAccount", "memoCode", "qrPayload");
            assertThat(f.jdbc.queryForObject("SELECT bank_account_id FROM nx_vietqr_intent", Long.class)).isNull();
            assertThat(f.jdbc.queryForObject("SELECT payment_rail FROM nx_vietqr_intent", String.class)).isEqualTo("HDPAY");
            assertThat(f.count("nx_vietqr_intent")).isEqualTo(1);
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            assertThat(f.count("nx_vietqr_reconciliation")).isEqualTo(1);
            assertThat(f.intents.findIntentForUser(42L, intentNo)).isNull();
            assertThatThrownBy(() -> canonical.cancel(41L, intentNo, "cancel-key", 0L))
                    .hasMessage("HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
            assertThat(f.jdbc.queryForObject("SELECT status FROM nx_vietqr_intent", String.class)).isEqualTo("AWAITING_PAYMENT");
            properties.setMode(HdPayProperties.Mode.DISABLED);
            assertThatThrownBy(() -> hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(hosted.get(41L, intentNo).getData()).containsEntry("paymentMode", "hosted")
                    .doesNotContainKeys("bankAccount", "memoCode", "paymentUrl");
            assertThatThrownBy(() -> hosted.cancel(41L, intentNo, "cancel-key", 0L))
                    .hasMessage("HDPAY_PROVIDER_ORDER_NOT_CANCELLABLE");
            f.bank();
            var manual = hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1").getData();
            assertThat(manual).containsKeys("bankAccount", "memoCode");
            properties.setMode(HdPayProperties.Mode.PROVIDER);
            assertThatThrownBy(() -> hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(hosted.get(41L, (String) manual.get("intentNo")).getData()).containsKeys("bankAccount", "memoCode");
            assertThat(f.count("nx_vietqr_intent")).isEqualTo(2);
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            verify(gateway, times(1)).createPayOrder(any());
            verifyNoMoreInteractions(gateway);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXION_HOSTED_RAIL_IT", matches = "true")
    void historicalProviderBackfillResumesTheExistingPageWithoutRepurposingManualOrders() throws Exception {
        inSchema(f -> {
            f.base(); f.providerSchema(); f.bank();
            f.legacyIntent("VQR-HOSTED001", "hosted-key");
            f.legacyIntent("VQR-MANUAL001", "manual-key");
            assertThat(f.orders.insertPending("VQR-HOSTED001", new BigDecimal("659750"), "a".repeat(64))).isEqualTo(1);
            // Historic provider fixture, before the new rail column exists.
            assertThat(f.jdbc.update("UPDATE nx_hdpay_payin_order SET submission_status='SUBMIT_UNKNOWN' WHERE merchant_order_id='VQR-HOSTED001'")).isEqualTo(1);
            assertThat(f.orders.markCreated("VQR-HOSTED001", "https://api.hdpayadmin.com/pay?id=history")).isEqualTo(1);
            f.migrate(MIGRATION); f.migrate(MIGRATION);
            assertThat(f.intents.findIntentByCreateKey(41L, "hosted-key"))
                    .containsEntry("paymentRail", "HDPAY").containsEntry("bankAccountId", 8L);
            assertThat(f.intents.findIntentByCreateKey(41L, "manual-key")).containsEntry("paymentRail", "MANUAL");
            HdPayGateway gateway = mock(HdPayGateway.class);
            var hosted = new HdPayHostedDepositService(f.service(), properties(), gateway, f.orders);
            assertThat(hosted.create(41L, "hosted-key", new BigDecimal("25"), "127.0.0.1").getData())
                    .containsEntry("paymentUrl", "https://api.hdpayadmin.com/pay?id=history")
                    .doesNotContainKeys("bankAccount", "memoCode");
            assertThatThrownBy(() -> hosted.create(41L, "manual-key", new BigDecimal("25"), "127.0.0.1"))
                    .hasMessage("VIETQR_PAYMENT_RAIL_CONFLICT");
            assertThat(f.intents.findIntentByMemoForUpdate("NX-hosted-key")).isNull();
            assertThat(f.intents.findIntentByMemoForUpdate("NX-manual-key")).isNotNull();
            assertThat(f.intents.sumActiveReservedVnd(8L)).isEqualByComparingTo("659750");
            assertThat(f.intents.findMaxAvailableBankCapacityVnd()).isEqualByComparingTo("9340250");
            assertThat(f.intents.cancelAwaitingIntentsForFusedAccount(8L, null)).isEqualTo(1);
            assertThat(f.intents.cancelActiveIntentsForBankAccount(8L)).isZero();
            assertThat(f.intents.findIntentByCreateKey(41L, "hosted-key")).containsEntry("status", "AWAITING_PAYMENT");
            assertThat(f.count("nx_hdpay_payin_order")).isEqualTo(1);
            verifyNoInteractions(gateway, f.cipher);
        });
    }

    private static class Fixture {
        final DriverManagerDataSource dataSource;
        final JdbcTemplate jdbc;
        final AppVietQrIntentMapper intents;
        final HdPayOrderMapper orders;
        final FinanceSensitiveDataCipher cipher = mock(FinanceSensitiveDataCipher.class);
        Fixture(String schema) {
            dataSource = dataSource(schema); jdbc = new JdbcTemplate(dataSource);
            Configuration c = new Configuration(new Environment("isolated-hosted-rail", new SpringManagedTransactionFactory(), dataSource));
            c.addMapper(AppVietQrIntentMapper.class); c.addMapper(HdPayOrderMapper.class);
            SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(c));
            intents = session.getMapper(AppVietQrIntentMapper.class); orders = session.getMapper(HdPayOrderMapper.class);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(schema);
        }
        void base() throws Exception {
            jdbc.execute("CREATE TABLE nx_config_item(config_key VARCHAR(100),config_value TEXT,updated_at DATETIME,is_deleted TINYINT)");
            jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(32),is_deleted TINYINT)");
            jdbc.update("INSERT INTO nx_user VALUES(41,'ACTIVE',0),(42,'ACTIVE',0)");
            migrate("20260725_vietnam_payment_real_tables.sql");
            migrate("20260725_vietqr_intent_app.sql");
            migrate("20260903_hdpay_commerce_direct_purchase.sql");
        }
        void providerSchema() throws Exception { migrate("20260901_hdpay_hosted_payin.sql"); }
        void migrate(String name) throws Exception {
            try (Connection c = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource("scripts/migrations/" + name));
            }
        }
        void bank() {
            jdbc.update("INSERT INTO nx_vietqr_bank_account(id,bank_code,bank_name,account_holder,account_number_encrypted,account_number_hash,account_number_last4,daily_cap_vnd) VALUES(8,'TEST','Isolated Bank','Fixture','cipher',?, '7890',10000000)", "a".repeat(64));
        }
        void legacyIntent(String no, String key) throws Exception {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("25.00".getBytes(StandardCharsets.UTF_8)));
            jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,bank_account_id,memo_code,expires_at) VALUES(?,41,?,?,25,659750,26390,0,8,?,UTC_TIMESTAMP()+INTERVAL 1 DAY)", no, key, hash, "NX-" + key);
        }
        AppVietQrIntentService service() {
            PlatformConfigFacade config = mock(PlatformConfigFacade.class);
            when(config.activeValue("finance.topup.channel.vietqr.enabled")).thenReturn(Optional.of("true"));
            when(cipher.decrypt(any(), any())).thenReturn("ISOLATED-ACCOUNT");
            MockEnvironment env = new MockEnvironment(); env.setActiveProfiles("prod");
            var target = new AppVietQrIntentService(intents, cipher, Clock.systemUTC(), env, config);
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
            return (AppVietQrIntentService) proxy.getProxy();
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    }

    private static HdPayProperties properties() {
        HdPayProperties p = new HdPayProperties(); p.setMode(HdPayProperties.Mode.PROVIDER);
        p.setBaseUrl("https://api.hdpayadmin.com/api/order"); p.setCallbackBaseUrl("https://payments.example.com");
        p.setCallbackHosts(java.util.List.of("payments.example.com")); p.setMerchantId("1234567890123456789");
        p.setMd5Key("0123456789abcdef0123456789abcdef"); return p;
    }
    private static String url(String endpoint, String schema) {
        if (!"127.0.0.1:13306".equals(endpoint)) throw new IllegalArgumentException("isolated endpoint required");
        if (schema == null || (!schema.isEmpty() && !schema.matches(PREFIX + "[a-f0-9]{32}"))) throw new IllegalArgumentException("owned UUID schema required");
        return "jdbc:mysql://" + endpoint + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
    private static DriverManagerDataSource dataSource(String schema) {
        return new DriverManagerDataSource(url(System.getenv("NEXION_ISOLATED_MYSQL_ENDPOINT"), schema), "root",
                System.getenv().getOrDefault("NEXION_ISOLATED_MYSQL_PASSWORD", ""));
    }
    private static void inSchema(SchemaTest test) throws Exception {
        String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(""));
        assertThat(admin.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        admin.execute("CREATE DATABASE " + schema);
        try { test.run(new Fixture(schema)); } finally { admin.execute("DROP DATABASE " + schema); }
    }
    @FunctionalInterface private interface SchemaTest { void run(Fixture fixture) throws Exception; }
}
