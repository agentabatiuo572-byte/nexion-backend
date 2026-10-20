package ffdd.opsconsole.shared.canonical;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import ffdd.opsconsole.common.boundary.PhoneActivationCapacityContract;
import ffdd.opsconsole.shared.exception.BizException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneActivationCapacityMySqlTest {
    private Connection admin;
    private String schema;
    private JdbcTemplate jdbc;
    private CanonicalStateMapper mapper;
    private PhoneActivationCapacityContract service;
    private TransactionTemplate transaction;
    private TransactionTemplate competingTransaction;
    private final AtomicLong ids = new AtomicLong(100);
    private boolean setupComplete;
    private boolean failed;
    @RegisterExtension final TestWatcher outcome = new TestWatcher() {
        @Override public void testFailed(ExtensionContext context, Throwable cause) { failed = true; }
    };

    @BeforeAll void setup() throws Exception {
        String url = System.getenv("NEXION_TEST_DB_SERVER_URL");
        if (url == null || !url.matches("jdbc:mysql://(127[.]0[.]0[.]1|localhost):[0-9]+/([?].*)?")) {
            throw new IllegalArgumentException("A dedicated loopback MySQL server URL without a catalog is required");
        }
        String username = System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME", "root");
        String password = System.getenv("NEXION_TEST_DB_PASSWORD");
        admin = DriverManager.getConnection(url, username, password);
        schema = "nx_phone_capacity_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        System.out.println("PHONE_CAPACITY_FIXTURE_CREATE=" + schema);
        int query = url.indexOf('?');
        var dataSource = new DriverManagerDataSource(
                url.substring(0, query < 0 ? url.length() : query) + schema
                        + (query < 0 ? "" : url.substring(query)), username, password);
        jdbc = new JdbcTemplate(dataSource);
        String baseline = Files.readString(Path.of("scripts/schema.sql"));
        for (String table : new String[] {"nx_user", "nx_user_device", "nx_order", "nx_order_item",
                "nx_product", "nx_trial_claim", "nx_config_item"}) {
            int start = baseline.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(baseline.substring(start, baseline.indexOf(';', start) + 1));
        }
        jdbc.execute("ALTER TABLE nx_trial_claim ADD COLUMN settled_at DATETIME(6) NULL");
        // Only the columns read by canonical capacity SQL; no promotion application writes run here.
        jdbc.execute("CREATE TABLE nx_promotion_reservation(reservation_id VARCHAR(64) PRIMARY KEY,"
                + "beneficiary_id BIGINT,asset VARCHAR(16),status VARCHAR(16),amount DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_reward(obligation_id VARCHAR(64) PRIMARY KEY,"
                + "reservation_id VARCHAR(64),status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_device_receipt(obligation_id VARCHAR(64),device_id BIGINT) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value) VALUES('device.max_active_slots','3')");
        jdbc.update("INSERT INTO nx_product(id,product_no,name,product_type,status) "
                + "VALUES(1,'capacity-box','Synthetic box','DEVICE','ACTIVE'),"
                + "(2,'capacity-share','Synthetic share','SHARE','ACTIVE')");
        var config = new Configuration(new Environment("phone-capacity", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(CanonicalStateMapper.class);
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(CanonicalStateMapper.class);
        var manager = new DataSourceTransactionManager(dataSource);
        var proxy = new ProxyFactory(new PhoneActivationCapacityService(mapper));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (PhoneActivationCapacityContract) proxy.getProxy();
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        competingTransaction = new TransactionTemplate(manager);
        competingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        setupComplete = true;
    }

    @BeforeEach void resetCap() {
        jdbc.update("UPDATE nx_config_item SET config_value='3' WHERE config_key='device.max_active_slots'");
    }

    @Test void onlyTheOwnedActiveCanonicalPhoneProvidesAReusableSlot() {
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,status,source_channel,activated_at) "
                + "VALUES(11,1,'CAPACITY-PHONE','Test phone','PHONE','OFFLINE','ONBOARDING',NOW())");
        assertThat(mapper.lockOccupiedOnboardingPhone(1L, 11L)).isEqualTo(11L);
        assertThat(mapper.lockOccupiedOnboardingPhone(2L, 11L)).isNull();
        assertThat(mapper.lockOccupiedOnboardingPhone(1L, 12L)).isNull();
        for (String mutation : new String[] {
                "deactivated_at=NOW()", "activated_at=NULL", "pending_deactivate=1", "is_deleted=1",
                "ownership_status='SOLD'", "source_channel='ORDER'", "device_type='BOX'", "status='INACTIVE'",
                "source_environment='SANDBOX',run_id='capacity-run'"}) {
            jdbc.update("UPDATE nx_user_device SET " + mutation + " WHERE id=11");
            assertThat(mapper.lockOccupiedOnboardingPhone(1L, 11L)).as(mutation).isNull();
            jdbc.update("UPDATE nx_user_device SET activated_at=NOW(),deactivated_at=NULL,pending_deactivate=0,"
                    + "is_deleted=0,ownership_status='OWNED',source_channel='ONBOARDING',device_type='PHONE',"
                    + "status='OFFLINE',source_environment='PRODUCTION',run_id='' WHERE id=11");
        }
        jdbc.update("UPDATE nx_user_device SET device_type='MOBILE' WHERE id=11");
        assertThat(mapper.lockOccupiedOnboardingPhone(1L, 11L)).isEqualTo(11L);
    }

    @Test void committedActiveSlotCannotHideBehindAnOlderSnapshotForEitherAccountEnvironment() {
        for (boolean sandbox : new boolean[] {false, true}) {
            long user = user(sandbox);
            device(user); device(user);
            afterOlderSnapshot(user, ignored -> device(user), ignored -> {
                assertThat(sandbox ? mapper.developmentActiveDeviceCount(user) : mapper.activeDeviceCount(user)).isEqualTo(2);
                assertThat(mapper.lockActiveDeviceCount(user, sandbox)).isEqualTo(3);
                assertFull(user);
            });
        }
    }

    @Test void committedOrderAndNewItemsAreReadAtEveryNestedLevel() {
        long user = user(false);
        device(user); device(user);
        // SHARE header deliberately makes an old/missing item snapshot return zero.
        afterOlderSnapshot(user, ignored -> {
            order(user, 2, "SINGLE", "PENDING_PAYMENT", "WAITING_PAYMENT");
            orderItem(user, 1);
        }, ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isZero();
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isEqualTo(1);
            assertFull(user);
        });
    }

    @Test void changedExistingOrderItemQuantityUsesCurrentRead() {
        long user = user(false);
        device(user);
        order(user, 2, "SINGLE", "PENDING_PAYMENT", "WAITING_PAYMENT");
        orderItem(user, 1);
        afterOlderSnapshot(user, ignored -> jdbc.update("UPDATE nx_order_item SET quantity=2 WHERE order_no=?", "ORDER-" + user), ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isEqualTo(2);
            assertFull(user);
        });
    }

    @Test void committedPromotionReservationCannotHideBehindAnOlderSnapshot() {
        for (boolean sandbox : new boolean[] {false, true}) {
            long user = user(sandbox);
            device(user); device(user);
            afterOlderSnapshot(user, ignored -> promotion(user), ignored -> {
                assertThat(sandbox ? mapper.developmentReservedDeviceOrderCount(user) : mapper.reservedDeviceOrderCount(user)).isZero();
                assertThat(mapper.lockReservedDeviceOrderCount(user, sandbox)).isEqualTo(1);
                assertFull(user);
            });
        }
    }

    @Test void fulfilledPromotionNestedExclusionUsesCurrentRead() {
        long user = user(false);
        device(user); device(user); promotion(user);
        jdbc.update("INSERT INTO nx_promotion_reward VALUES(?,?,'ISSUED')", "REWARD-" + user, "PROMO-" + user);
        afterOlderSnapshot(user, ignored -> jdbc.update("INSERT INTO nx_promotion_device_receipt VALUES(?,?)", "REWARD-" + user, user), ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isZero();
            service.requireCapacity(user, null);
        });
    }

    @Test void fulfilledLegacyTrialNestedExclusionUsesCurrentRead() {
        long user = user(false);
        device(user); device(user);
        order(user, 1, "TRIAL_CONVERT", "PAID", "ACTIVE");
        afterOlderSnapshot(user, ignored -> {
            long trialDevice = device(user);
            jdbc.update("UPDATE nx_user_device SET source_channel='TRIAL',source_order_no=?,product_id=1,"
                    + "deactivated_at=NOW(),status='INACTIVE' WHERE id=?", "ORDER-" + user, trialDevice);
            jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,status,user_device_id,device_name,claimed_at,expires_at,settled_at) "
                    + "VALUES(?,?,'REDEEMED',?,'Synthetic trial',NOW(),NOW(),NOW())", user, "TRIAL-" + user, trialDevice);
        }, ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isZero();
            service.requireCapacity(user, null);
        });
    }

    @Test void changedCapacityConfigurationUsesCurrentRead() {
        long user = user(false);
        device(user); device(user);
        afterOlderSnapshot(user, ignored -> jdbc.update("UPDATE nx_config_item SET config_value='2' WHERE config_key='device.max_active_slots'"), ignored -> {
            assertThat(mapper.deviceSlotCap()).isEqualTo(3);
            assertThat(mapper.lockDeviceSlotCap()).isEqualTo(2);
            assertFull(user);
        });
    }

    @Test void capacityRejectionAllowsTheOuterTransactionToPersistItsRejectionReceipt() {
        long user = user(false);
        device(user); device(user); device(user);
        String receipt = "capacity.test.rejection." + user;
        transaction.executeWithoutResult(status -> {
            assertFull(user);
            assertThat(status.isRollbackOnly()).isFalse();
            jdbc.update("INSERT INTO nx_config_item(config_key,config_value) VALUES(?,'REJECTED')", receipt);
        });
        assertThat(jdbc.queryForObject("SELECT config_value FROM nx_config_item WHERE config_key=?", String.class, receipt))
                .isEqualTo("REJECTED");
    }

    private long user(boolean sandbox) {
        long id = ids.incrementAndGet();
        jdbc.update("INSERT INTO nx_user(id,country_code,phone,client_ip,password_hash,nickname,referral_code,sandbox) "
                + "VALUES(?,'+0',?,'127.0.0.1','not-authenticatable','Synthetic capacity user',?,?)",
                id, "SYNTHETIC-" + id, "CAPACITY-" + id, sandbox);
        return id;
    }

    private long device(long user) {
        long id = ids.incrementAndGet();
        jdbc.update("INSERT INTO nx_user_device(id,user_id,instance_no,name,device_type,status,activated_at) "
                + "VALUES(?, ?, ?, 'Synthetic capacity device','BOX','ACTIVE',NOW())", id, user, "DEVICE-" + id);
        return id;
    }

    private void order(long user, int product, String type, String status, String activation) {
        jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,amount_usdt,payment_status,order_status,activation_status,order_type) "
                + "VALUES(?,?,?,1,'PAID',?,?,?)", user, "ORDER-" + user, product, status, activation, type);
    }

    private void orderItem(long user, int quantity) {
        jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_name,quantity,unit_price_usdt,line_amount_usdt) "
                + "VALUES(?,1,'Synthetic box',?,1,?)", "ORDER-" + user, quantity, quantity);
    }

    private void promotion(long user) {
        jdbc.update("INSERT INTO nx_promotion_reservation VALUES(?,?,'DEVICE','RESERVED',1)", "PROMO-" + user, user);
    }

    private void afterOlderSnapshot(long user, Consumer<Long> commitOther, Consumer<Long> check) {
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            jdbc.queryForObject("SELECT sandbox FROM nx_user WHERE id=?", Integer.class, user);
            competingTransaction.executeWithoutResult(other -> {
                jdbc.queryForObject("SELECT id FROM nx_user WHERE id=? FOR UPDATE", Long.class, user);
                commitOther.accept(user);
            });
            // A separate connection has committed after our read view was created, before our account lock.
            assertThat(mapper.lockUser(user)).isNotNull();
            check.accept(user);
        });
    }

    private void assertFull(long user) {
        assertThatThrownBy(() -> service.requireCapacity(user, null))
                .isInstanceOf(BizException.class).hasMessage("CAPACITY_REPLACEMENT_REQUIRED");
    }

    @AfterAll void cleanup() throws Exception {
        if (admin == null) return;
        try {
            if (setupComplete && !failed) {
                try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE `" + schema + "`"); }
                System.out.println("PHONE_CAPACITY_FIXTURE_DROP=" + schema);
            } else System.out.println("PHONE_CAPACITY_FIXTURE_RETAINED=" + schema);
        } finally { admin.close(); }
    }
}
