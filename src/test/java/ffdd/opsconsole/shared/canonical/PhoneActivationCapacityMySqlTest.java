package ffdd.opsconsole.shared.canonical;

import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.canonical.mapper.AppBundleOrderMapper;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.device.infrastructure.MybatisDeviceOpsRepository;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.mock.env.MockEnvironment;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named = "NEXION_TEST_DB_PASSWORD", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneActivationCapacityMySqlTest {
    private Connection admin;
    private String schema;
    private JdbcTemplate jdbc;
    private CanonicalStateMapper mapper;
    private AppBundleOrderMapper bundle;
    private PromotionMapper promotionMapper;
    private AppTradeinMapper tradeinMapper;
    private DeviceOpsMapper opsMapper;
    private AppOrderCommandMapper paymentMapper;
    private AppHomeOverviewMapper homeMapper;
    private AppTaskAssignmentMapper taskMapper;
    private AppCanonicalBoundaryService canonicalService;
    private MybatisDeviceOpsRepository deviceRepository;
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
        var connectionProperties = new java.util.Properties();
        connectionProperties.setProperty("sessionVariables", "time_zone='+00:00'");
        dataSource.setConnectionProperties(connectionProperties);
        jdbc = new JdbcTemplate(dataSource);
        String baseline = Files.readString(Path.of("scripts/schema.sql"));
        for (String table : new String[] {"nx_user", "nx_user_device", "nx_order", "nx_order_item",
                "nx_product", "nx_trial_claim", "nx_config_item"}) {
            int start = baseline.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(baseline.substring(start, baseline.indexOf(';', start) + 1));
        }
        jdbc.execute("ALTER TABLE nx_trial_claim ADD COLUMN settled_at DATETIME(6) NULL");
        // Production delivery allocates IDs too; keep the explicit phone fixture at 11 separate.
        jdbc.execute("ALTER TABLE nx_user_device AUTO_INCREMENT=1000");
        jdbc.execute("CREATE TABLE nx_admin_device_sku(sku_id VARCHAR(64),power_text VARCHAR(40),datacenter VARCHAR(60),is_deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO nx_admin_device_sku VALUES('capacity-box','120 W','fixture DC',0)");
        jdbc.execute("CREATE TABLE nx_compute_task(user_id BIGINT,user_device_id BIGINT,status VARCHAR(32),"
                + "source_environment VARCHAR(16) DEFAULT 'PRODUCTION',is_deleted INT DEFAULT 0)");
        // Only the columns read by canonical capacity SQL; no promotion application writes run here.
        jdbc.execute("CREATE TABLE nx_promotion_reservation(reservation_id VARCHAR(64) PRIMARY KEY,"
                + "beneficiary_id BIGINT,asset VARCHAR(16),status VARCHAR(16),amount DECIMAL(18,6)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_reward(obligation_id VARCHAR(64) PRIMARY KEY,"
                + "reservation_id VARCHAR(64),status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_promotion_device_receipt(obligation_id VARCHAR(64),device_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE nx_compute_share_enrollment(id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + "enrollment_no VARCHAR(80),user_id BIGINT,requested_gpu_model VARCHAR(80),pairing_code_hash VARCHAR(64),"
                + "status VARCHAR(32),user_device_id BIGINT,device_instance_no VARCHAR(80),expires_at DATETIME(6),"
                + "claimed_at DATETIME,created_at DATETIME,updated_at DATETIME,"
                + "row_version BIGINT DEFAULT 0,is_deleted INT DEFAULT 0) ENGINE=InnoDB");
        jdbc.update("INSERT INTO nx_config_item(config_key,config_value) VALUES('device.max_active_slots','6')");
        jdbc.update("INSERT INTO nx_product(id,product_no,name,product_type,status) "
                + "VALUES(1,'capacity-box','Synthetic box','DEVICE','ACTIVE'),"
                + "(2,'capacity-share','Synthetic share','SHARE','ACTIVE'),"
                + "(3,'other-capacity-box','Other synthetic box','DEVICE','ACTIVE')");
        var config = new Configuration(new Environment("phone-capacity", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(CanonicalStateMapper.class);
        config.addMapper(DeviceOpsMapper.class);
        config.addMapper(AppBundleOrderMapper.class);
        config.addMapper(PromotionMapper.class);
        config.addMapper(AppTradeinMapper.class);
        config.addMapper(AppOrderCommandMapper.class);
        config.addMapper(AppHomeOverviewMapper.class);
        config.addMapper(AppTaskAssignmentMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper = session.getMapper(CanonicalStateMapper.class);
        bundle = session.getMapper(AppBundleOrderMapper.class);
        promotionMapper = session.getMapper(PromotionMapper.class);
        tradeinMapper = session.getMapper(AppTradeinMapper.class);
        opsMapper = session.getMapper(DeviceOpsMapper.class);
        paymentMapper = session.getMapper(AppOrderCommandMapper.class);
        homeMapper = session.getMapper(AppHomeOverviewMapper.class);
        taskMapper = session.getMapper(AppTaskAssignmentMapper.class);
        var platform = mock(PlatformConfigFacade.class);
        var idempotency = mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        canonicalService = new AppCanonicalBoundaryService(mapper,
                mock(ffdd.opsconsole.risk.facade.TamperDetectionPublisher.class), idempotency,
                mock(EventOutboxService.class), mock(ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.class),
                mock(ffdd.opsconsole.growth.facade.GrowthRhythmFacade.class), mock(AuditLogService.class),
                mock(ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper.class),
                mock(ffdd.opsconsole.finance.application.FundsSandboxProfileGuard.class),
                new ffdd.opsconsole.commerce.application.CommerceAcceptanceRun("inventory-capacity-test"),
                mock(StorefrontProductReleasePolicy.class), new StorefrontPurchaseGatePolicy(), environment);
        deviceRepository = new MybatisDeviceOpsRepository(session.getMapper(DeviceOpsMapper.class), mock(OpsReadTimeSeedPolicy.class), mapper);
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
        jdbc.update("UPDATE nx_config_item SET config_value='6' WHERE config_key='device.max_active_slots'");
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
            for (int i=0; i<5; i++) device(user);
            afterOlderSnapshot(user, ignored -> device(user), ignored -> {
                assertThat(sandbox ? mapper.developmentActiveDeviceCount(user) : mapper.activeDeviceCount(user)).isEqualTo(5);
                assertThat(mapper.lockActiveDeviceCount(user, sandbox)).isEqualTo(6);
                assertFull(user);
            });
        }
    }

    @Test void committedOrderAndNewItemsAreReadAtEveryNestedLevel() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user);
        // The old snapshot must not see the newly committed Cloud header or its item.
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
        for (int i=0; i<4; i++) device(user);
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
            for (int i=0; i<5; i++) device(user);
            afterOlderSnapshot(user, ignored -> promotion(user), ignored -> {
                assertThat(sandbox ? mapper.developmentReservedDeviceOrderCount(user) : mapper.reservedDeviceOrderCount(user)).isZero();
                assertThat(mapper.lockReservedDeviceOrderCount(user, sandbox)).isEqualTo(1);
                assertFull(user);
            });
        }
    }

    @Test void committedTrialReservationCannotHideBehindAnOlderSnapshot() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user);
        afterOlderSnapshot(user, ignored -> jdbc.update(
                "INSERT INTO nx_trial_claim(user_id,claim_no,status,device_name,claimed_at,expires_at) "
                        + "VALUES(?,?,'ACTIVE','Synthetic trial',NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY))",
                user, "TRIAL-" + user), ignored -> {
            assertThat(mapper.reservedTrialSlotCount(user)).isZero();
            assertThat(mapper.lockReservedTrialSlotCount(user)).isEqualTo(1);
            assertFull(user);
        });
    }

    @Test void fulfilledPromotionNestedExclusionUsesCurrentRead() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user); promotion(user);
        jdbc.update("INSERT INTO nx_promotion_reward VALUES(?,?,'ISSUED')", "REWARD-" + user, "PROMO-" + user);
        afterOlderSnapshot(user, ignored -> jdbc.update("INSERT INTO nx_promotion_device_receipt VALUES(?,?)", "REWARD-" + user, user), ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isZero();
            service.requireCapacity(user, null);
        });
    }

    @Test void fulfilledLegacyTrialNestedExclusionUsesCurrentRead() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2", "12"})
    void legacyCapacityConfigurationCannotChangeTheFixedSixSlots(String configured) {
        long user = user(false);
        for (int i=0; i<6; i++) device(user);
        afterOlderSnapshot(user, ignored -> jdbc.update("UPDATE nx_config_item SET config_value=? WHERE config_key='device.max_active_slots'", configured), ignored -> {
            assertThat(mapper.deviceSlotCap()).isEqualTo(6);
            assertThat(mapper.lockDeviceSlotCap()).isEqualTo(6);
            assertFull(user);
        });
    }

    @Test void cloudShareOccupiesTheSixthSlotAcrossEveryCapacityReader() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user);
        long cloud = device(user);
        jdbc.update("UPDATE nx_user_device SET device_type='SHARE',product_code='cloud-share' WHERE id=?", cloud);
        assertThat(mapper.activeDeviceCount(user)).isEqualTo(6);
        assertThat(mapper.lockActiveDeviceCount(user, false)).isEqualTo(6);
        assertThat(bundle.activeDeviceCount(user)).isEqualTo(6);
        assertThat(bundle.lockActiveDeviceCount(user)).isEqualTo(6);
        assertThat(tradeinMapper.countActiveDevices(user)).isEqualTo(6);
        assertThat(tradeinMapper.lockActiveDeviceCount(user)).isEqualTo(6);
        assertThat(opsMapper.countActiveDevicesByUser(user)).isEqualTo(6);
        assertThat(promotionMapper.occupiedDeviceSlots(user)).isEqualTo(6);
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(6);
        assertThat(homeMapper.activeSlotCount(user)).isEqualTo(6);
        transaction.executeWithoutResult(status -> assertFull(user));
        jdbc.update("UPDATE nx_user_device SET status='DEACTIVATED',deactivated_at=NOW() WHERE id=?", cloud);
        assertThat(mapper.activeDeviceCount(user)).isEqualTo(5);
        transaction.executeWithoutResult(status -> service.requireCapacity(user, null));
    }

    @Test void pendingDeactivationKeepsTheSixthSlotUntilTheOriginalTaskActuallyStops() {
        long user = user(false);
        for (int i=0; i<5; i++) device(user);
        long pending = device(user);
        jdbc.update("UPDATE nx_user_device SET device_type='SHARE',product_code='cloud-share' WHERE id=?", pending);
        long inventory = device(user);
        jdbc.update("UPDATE nx_user_device SET status='INACTIVE',activated_at=NULL WHERE id=?", inventory);
        jdbc.update("INSERT INTO nx_compute_task(user_id,user_device_id,status) VALUES(?,?,'RUNNING')", user, pending);
        transaction.executeWithoutResult(status -> {
            var deferred = canonicalService.deactivateAfterTask(user, pending, 0L, "pending-sixth-" + user);
            assertThat(deferred.getCode()).as(deferred.getMessage()).isZero();
            assertThat(deferred.getData()).containsEntry("status", "PENDING_DEACTIVATE");
            assertThat(jdbc.queryForObject("SELECT status FROM nx_user_device WHERE id=?", String.class, pending)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT pending_deactivate FROM nx_user_device WHERE id=?", Integer.class, pending)).isEqualTo(1);
            assertThat(mapper.lockActiveDeviceCount(user, false)).isEqualTo(6);
            assertThat(bundle.lockActiveDeviceCount(user)).isEqualTo(6);
            assertThat(tradeinMapper.lockActiveDeviceCount(user)).isEqualTo(6);
            assertThat(opsMapper.countActiveDevicesByUser(user)).isEqualTo(6);
            assertThat(promotionMapper.occupiedDeviceSlots(user)).isEqualTo(6);
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isEqualTo(6);
            assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(6);
            assertThat(homeMapper.activeSlotCount(user)).isEqualTo(6);
            assertThat(homeMapper.activeDevices(user, false)).isEqualTo(6);
            assertFull(user);
            var seventh = canonicalService.activateDevice(user, inventory, 0L, 6, "seventh-pending-" + user);
            assertThat(seventh.getCode()).isEqualTo(409);
            assertThat(seventh.getMessage()).isEqualTo("DEVICE_SLOT_CAP_EXCEEDED");
            assertThat(mapper.activateOwnedDeviceCas(user, inventory, 0L, 6)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM nx_compute_task WHERE user_id=? AND user_device_id=?",
                    String.class, user, pending)).isEqualTo("RUNNING");
        });
        // Synthetic completion observation; run the same lifecycle SQL used after a real task settles.
        jdbc.update("UPDATE nx_compute_task SET status='COMPLETED' WHERE user_id=? AND user_device_id=?", user, pending);
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.lockUser(user)).isNotNull();
            assertThat(mapper.hasActiveTask(user, pending)).isFalse();
            assertThat(taskMapper.deactivatePendingDevice(user, pending, java.time.LocalDateTime.now())).isEqualTo(1);
            assertThat(mapper.lockActiveDeviceCount(user, false)).isEqualTo(5);
            var sixth = canonicalService.activateDevice(user, inventory, 0L, 6, "sixth-after-completion-" + user);
            assertThat(sixth.getCode()).as(sixth.getMessage()).isZero();
            assertThat(sixth.getData()).containsEntry("activeCount", 6);
        });
    }

    @Test void cloudShareOrdersReserveEveryHeaderOrItemUnit() {
        long user = user(false);
        order(user, 2, "SINGLE", "PENDING_PAYMENT", "WAITING_PAYMENT");
        jdbc.update("UPDATE nx_order SET quantity=2 WHERE order_no=?", "ORDER-" + user);
        assertCapacityKeepReservations(user, 2);
        jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_name,quantity,unit_price_usdt,line_amount_usdt) "
                + "VALUES(?,2,'Synthetic share',3,1,3)", "ORDER-" + user);
        assertCapacityKeepReservations(user, 3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PENDING_PAYMENT,WAITING_PAYMENT", "PAID,WAITING_PROVISIONING", "PROCESSING,PROVISIONING"})
    void paymentCountsItsOwnReservationOnceAndSeesTrialAndTerminalRelease(String orderState, String activationState) {
        long user=user(false);
        for(int i=0;i<5;i++)device(user);
        order(user,2,"SINGLE",orderState,activationState);
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(6);
        assertThat(homeMapper.reservedDeviceOrderCount(user)).isEqualTo(1);
        trial(user,"ACTIVE");
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=?",Integer.class,user)).isEqualTo(5);
        jdbc.update("UPDATE nx_order SET order_status='CANCELLED',activation_status='CANCELLED' WHERE order_no=?","ORDER-"+user);
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(6);
        jdbc.update("UPDATE nx_trial_claim SET status='CANCELLED' WHERE user_id=?",user);
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(5);
    }

    @Test void committedCapacityChangesRefreshPaymentSqlEvenAfterItsOldSnapshot() {
        long user=user(false);
        for(int i=0;i<5;i++)device(user);
        afterOlderSnapshot(user, ignored -> {
            order(user,2,"SINGLE","PENDING_PAYMENT","WAITING_PAYMENT");
            orderItem(user,2);
            trial(user,"ACTIVE");
        }, ignored -> assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(8));
    }

    @Test void trialAndShareCommandsCannotBothReserveTheLastSlot() throws Exception {
        long user=user(false);
        for(int i=0;i<5;i++)device(user);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var trialCommand=pool.submit(() -> {
                ready.countDown();start.await();
                return transaction.execute(status -> {
                    mapper.lockUser(user);
                    if(mapper.lockOccupiedDeviceSlotCount(user,false)>=6)return false;
                    trial(user,"ACTIVE");return true;
                });
            });
            var shareCommand=pool.submit(() -> {
                ready.countDown();start.await();
                return transaction.execute(status -> {
                    mapper.lockUser(user);
                    if(mapper.lockOccupiedDeviceSlotCount(user,false)>=6)return false;
                    order(user,2,"SINGLE","PENDING_PAYMENT","WAITING_PAYMENT");return true;
                });
            });
            ready.await();start.countDown();
            int successful=(trialCommand.get(20,java.util.concurrent.TimeUnit.SECONDS)?1:0)
                    +(shareCommand.get(20,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertThat(successful).isEqualTo(1);
            assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user)).isEqualTo(6);
        } finally { pool.shutdownNow(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deliveredCapacityKeepInventoryCanUseTheLastSlotAndIsNotReservedAgain(boolean withTrial) {
        long user = user(false);
        jdbc.update("UPDATE nx_config_item SET config_value='6' WHERE config_key='device.max_active_slots'");
        for (int i=0; i<(withTrial ? 4 : 5); i++) device(user);
        if (withTrial) trial(user, "ACTIVE");
        capacityKeepOrder(user);
        long inventory = deliverCapacityKeep(user);
        var paidOrder = jdbc.queryForMap("SELECT user_id,amount_usdt,payment_status,order_status,activation_status FROM nx_order WHERE order_no=?", "ORDER-" + user);
        transaction.executeWithoutResult(status -> {
            assertThat(mapper.lockUser(user)).isNotNull();
            assertCapacityKeepReservations(user, 0);
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isEqualTo(5);
            service.requireCapacity(user, null);
            var result = canonicalService.activateDevice(user, inventory, 0L, 6, "activate-inventory-" + user);
            assertThat(result.getCode()).as(result.getMessage()).isZero();
            assertCapacityKeepReservations(user, 0);
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isEqualTo(6);
            assertFull(user);
        });
        assertThat(jdbc.queryForMap("SELECT user_id,amount_usdt,payment_status,order_status,activation_status FROM nx_order WHERE order_no=?", "ORDER-" + user))
                .isEqualTo(paidOrder);
        assertThat(jdbc.queryForMap("SELECT status,ownership_status FROM nx_user_device WHERE id=?", inventory))
                .containsEntry("status", "ACTIVE").containsEntry("ownership_status", "OWNED");
        assertThat(mapper.activeDeviceCount(user)).isEqualTo(withTrial ? 5 : 6);
    }

    @Test void capacityKeepDeliveryMustMatchItsSinglePurchasedUnit() {
        for (String mutation : new String[] {
                "UPDATE nx_user_device SET user_id=0 WHERE source_order_no=?",
                "UPDATE nx_user_device SET source_order_no='WRONG-ORDER' WHERE source_order_no=?",
                "UPDATE nx_user_device SET product_id=2 WHERE source_order_no=?",
                "UPDATE nx_user_device SET is_deleted=1 WHERE source_order_no=?",
                "UPDATE nx_user_device SET source_environment='SANDBOX',run_id='inventory-test-run' WHERE source_order_no=?",
                "UPDATE nx_user_device SET source_channel='TRIAL' WHERE source_order_no=?",
                "UPDATE nx_user_device SET purchased_at=NULL WHERE source_order_no=?",
                "UPDATE nx_order_item SET is_deleted=1 WHERE order_no=?",
                "UPDATE nx_order_item SET product_id=3 WHERE order_no=?",
                "UPDATE nx_order_item SET quantity=2 WHERE order_no=?",
                "UPDATE nx_order SET quantity=2 WHERE order_no=?",
                "UPDATE nx_order SET item_count=2 WHERE order_no=?",
                "UPDATE nx_order SET payment_status='PENDING' WHERE order_no=?",
                "UPDATE nx_order SET paid_at=NULL WHERE order_no=?",
                "UPDATE nx_order SET order_type='SINGLE' WHERE order_no=?"}) {
            long user = user(false);
            capacityKeepOrder(user);
            deliverCapacityKeep(user);
            jdbc.update(mutation, "ORDER-" + user);
            assertThat(mapper.reservedDeviceOrderCount(user)).as(mutation).isPositive();
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).as(mutation).isPositive();
            assertThat(bundle.reservedDeviceOrderCount(user)).as(mutation).isPositive();
            assertThat(promotionMapper.occupiedDeviceSlots(user)).as(mutation).isPositive();
        }
        long missing = user(false);
        capacityKeepOrder(missing);
        assertCapacityKeepReservations(missing, 1);
        long duplicate = user(false);
        capacityKeepOrder(duplicate);
        deliverCapacityKeep(duplicate);
        orderItem(duplicate, 1);
        assertCapacityKeepReservations(duplicate, 2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"inventory", "recycled", "unbound"})
    void laterCapacityKeepLifecycleDoesNotRecreateAnOrderReservation(String transition) {
        long user = user(false);
        capacityKeepOrder(user);
        long inventory = deliverCapacityKeep(user);
        transaction.executeWithoutResult(status -> {
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isZero();
            assertThat(opsMapper.activateE5Device(inventory, java.time.LocalDateTime.now())).isEqualTo(1);
            assertCapacityKeepReservations(user, 0);
            if ("unbound".equals(transition)) {
                assertThat(opsMapper.deactivateE5Device(inventory, 1, java.time.LocalDateTime.now())).isEqualTo(1);
            } else {
                jdbc.update("UPDATE nx_user_device SET status='ACTIVE' WHERE id=?", inventory);
                assertThat("inventory".equals(transition)
                        ? tradeinMapper.moveSourceDeviceToInventory(user, inventory)
                        : tradeinMapper.recycleSourceDevice(user, inventory)).isEqualTo(1);
            }
            assertCapacityKeepReservations(user, 0);
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isZero();
        });
        assertThat(mapper.activeDeviceCount(user)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"inventory", "item"})
    void committedCapacityKeepDeliveryUsesCurrentReadsAtEveryNestedLevel(String committedFact) {
        long user = user(false);
        device(user); device(user); capacityKeepOrder(user);
        if ("item".equals(committedFact)) {
            deliverCapacityKeep(user);
            jdbc.update("UPDATE nx_order_item SET is_deleted=1 WHERE order_no=?", "ORDER-" + user);
        }
        afterOlderSnapshot(user, ignored -> {
            if ("item".equals(committedFact)) orderItem(user, 1);
            else deliverCapacityKeep(user);
        }, ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isZero();
            assertThat(tradeinMapper.reservedDeviceOrderCount(user)).isEqualTo(1);
            assertThat(tradeinMapper.lockReservedDeviceOrderCount(user)).isZero();
            assertThat(tradeinMapper.lockActiveDeviceCount(user)).isEqualTo(2);
            assertThat(deviceRepository.countOccupiedSlotsForE5Activation(user)).isEqualTo(2);
            assertThat(promotionMapper.occupiedDeviceSlots(user)).isEqualTo(2);
            service.requireCapacity(user, null);
        });
    }

    @Test void aNewExtraCapacityKeepItemCannotHideBehindAnOlderSnapshot() {
        long user = user(false);
        for (int i=0; i<4; i++) device(user);
        capacityKeepOrder(user); deliverCapacityKeep(user);
        afterOlderSnapshot(user, ignored -> orderItem(user, 1), ignored -> {
            assertThat(mapper.reservedDeviceOrderCount(user)).isZero();
            assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isEqualTo(2);
            assertThat(tradeinMapper.lockReservedDeviceOrderCount(user)).isEqualTo(2);
            assertThat(promotionMapper.occupiedDeviceSlots(user)).isEqualTo(6);
            assertFull(user);
        });
    }

    private void assertCapacityKeepReservations(long user, int expected) {
        assertThat(mapper.reservedDeviceOrderCount(user)).isEqualTo(expected);
        assertThat(mapper.lockReservedDeviceOrderCount(user, false)).isEqualTo(expected);
        assertThat(bundle.reservedDeviceOrderCount(user)).isEqualTo(expected);
        assertThat(tradeinMapper.reservedDeviceOrderCount(user)).isEqualTo(expected);
        assertThat(tradeinMapper.lockReservedDeviceOrderCount(user)).isEqualTo(expected);
        assertThat(promotionMapper.occupiedDeviceSlots(user))
                .isEqualTo((long) mapper.activeDeviceCount(user) + expected + mapper.lockReservedTrialSlotCount(user));
        assertThat(paymentMapper.lockOccupiedDeviceSlotCount(user))
                .isEqualTo((long) mapper.activeDeviceCount(user) + expected + mapper.lockReservedTrialSlotCount(user));
        assertThat(homeMapper.reservedDeviceOrderCount(user)).isEqualTo(expected);
    }

    private void capacityKeepOrder(long user) {
        var row = new AppTradeinMapper.PaidOrderWrite(user, "ORDER-" + user, 1L, "capacity-box", "Synthetic box",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null);
        assertThat(tradeinMapper.insertCapacityKeepOrder(row)).isEqualTo(1);
        assertThat(tradeinMapper.insertPaidOrderItem(row)).isEqualTo(1);
        // Preserve historical paid-but-unactivated deliveries when testing legacy reservation compatibility.
        jdbc.update("UPDATE nx_order SET order_status='PAID',activation_status='WAITING_PROVISIONING' WHERE order_no=?", "ORDER-" + user);
    }

    private long deliverCapacityKeep(long user) {
        String instance = "INVENTORY-" + user;
        var row = new AppTradeinMapper.DeliveredDeviceWrite(user, "ORDER-" + user, 1L, "capacity-box", "S1",
                instance, "Synthetic box", "BOX", 1, "fixture GPU", 8,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(tradeinMapper.insertCapacityKeepTargetDevice(row)).isEqualTo(1);
        jdbc.update("UPDATE nx_user_device SET status='INACTIVE',activated_at=NULL,last_seen_at=NULL WHERE instance_no=?", instance);
        return tradeinMapper.findDeviceIdByInstanceNo(instance);
    }

    @Test void capacityRejectionAllowsTheOuterTransactionToPersistItsRejectionReceipt() {
        long user = user(false);
        for (int i=0; i<6; i++) device(user);
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
        String instance = "DEVICE-" + ids.incrementAndGet();
        jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,activated_at) "
                + "VALUES(?, ?, 'Synthetic capacity device','BOX','ACTIVE',NOW())", user, instance);
        return jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?", Long.class, instance);
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

    private void trial(long user, String state) {
        jdbc.update("INSERT INTO nx_trial_claim(user_id,claim_no,status,device_name,claimed_at,expires_at) "
                + "VALUES(?,?,?,'Synthetic trial',NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY))",user,"TRIAL-"+user,state);
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
