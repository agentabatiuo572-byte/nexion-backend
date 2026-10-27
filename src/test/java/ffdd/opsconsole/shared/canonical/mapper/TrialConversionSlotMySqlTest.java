package ffdd.opsconsole.shared.canonical.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.device.mapper.DeviceCatalogMapper;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.device.mapper.AppComputeShareEnrollmentMapper;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real production insert/count SQL in an owned empty MySQL schema; no product fixture. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrialConversionSlotMySqlTest {
    Connection admin; String schema; boolean created; JdbcTemplate jdbc;
    DeviceCatalogMapper catalog; CanonicalStateMapper slots; AppTrialLifecycleMapper trial; AppBundleOrderMapper bundle; PromotionMapper promotion;
    @BeforeAll void setup() throws Exception {
        String url=System.getenv("NEXION_TEST_DB_SERVER_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/([?].*)?")) throw new IllegalArgumentException("Server URL without catalog required");
        String user=System.getenv().getOrDefault("NEXION_TEST_DB_USERNAME","root");
        String password=System.getenv("NEXION_TEST_DB_PASSWORD");
        admin=DriverManager.getConnection(url,user,password);
        schema="nx_trial_slots_test_"+UUID.randomUUID().toString().replace("-","");
        try(var s=admin.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created=true;}
        System.out.println("TRIAL_SLOTS_FIXTURE_CREATE="+schema);
        int q=url.indexOf('?');
        String fixture=url.substring(0,q<0?url.length():q)+schema+(q<0?"":url.substring(q));
        var ds=new DriverManagerDataSource(fixture,user,password); jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,sandbox INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_product(id BIGINT PRIMARY KEY,product_no VARCHAR(64),product_type VARCHAR(32),estimated_daily_usdt DECIMAL(24,6),daily_nex DECIMAL(24,6),gpu_model VARCHAR(80),vram_total_gb DECIMAL(12,3),is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_admin_device_sku(sku_id VARCHAR(64),power_text VARCHAR(40),datacenter VARCHAR(60),is_deleted INT DEFAULT 0)");
        jdbc.execute("""
          CREATE TABLE nx_order(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT,order_no VARCHAR(80) UNIQUE,product_id BIGINT,quantity INT,
          order_type VARCHAR(32),item_count INT,subtotal_usdt DECIMAL(24,6),discount_usdt DECIMAL(24,6),amount_usdt DECIMAL(24,6),
          payment_status VARCHAR(32),order_status VARCHAR(32),activation_status VARCHAR(32),paid_at DATETIME(6),payment_no VARCHAR(80),created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0)
          """);
        jdbc.execute("CREATE TABLE nx_order_item(id BIGINT PRIMARY KEY AUTO_INCREMENT,order_no VARCHAR(80),product_id BIGINT,product_no VARCHAR(64),product_name VARCHAR(80),quantity INT,unit_price_usdt DECIMAL(24,6),line_amount_usdt DECIMAL(24,6),sort_order INT,created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0)");
        jdbc.execute("""
          CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT,source_order_no VARCHAR(80),product_id BIGINT,
          product_code VARCHAR(64),product_tier VARCHAR(32),device_id VARCHAR(64),instance_no VARCHAR(64),name VARCHAR(80),device_type VARCHAR(32),
          generation INT,base_power_w DECIMAL(24,6),price_usdt_snapshot DECIMAL(24,6),ownership_status VARCHAR(32),source_channel VARCHAR(32),
          status VARCHAR(32),hashrate DECIMAL(24,6),daily_usdt DECIMAL(24,6),daily_nex DECIMAL(24,6),purchased_at DATETIME,activated_at DATETIME,
          created_at DATETIME,updated_at DATETIME,is_deleted INT DEFAULT 0,gpu_model VARCHAR(80),vram_total_gb DECIMAL(12,3),dc_location VARCHAR(60),
          source_environment VARCHAR(32) DEFAULT 'PRODUCTION',run_id VARCHAR(80) DEFAULT '',deactivated_at DATETIME,pending_deactivate INT DEFAULT 0,row_version BIGINT DEFAULT 0)
          """);
        jdbc.execute("CREATE TABLE nx_trial_claim(id BIGINT PRIMARY KEY,user_id BIGINT,user_device_id BIGINT,status VARCHAR(32),settled_at DATETIME,is_deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_promotion_reservation(reservation_id BIGINT,beneficiary_id BIGINT,asset VARCHAR(16),status VARCHAR(32),amount INT)");
        jdbc.execute("CREATE TABLE nx_promotion_reward(obligation_id BIGINT,reservation_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE nx_promotion_device_receipt(obligation_id BIGINT,device_id BIGINT)");
        jdbc.execute("ALTER TABLE nx_product ADD name VARCHAR(80), ADD price_usdt DECIMAL(24,6)");
        jdbc.execute("ALTER TABLE nx_trial_claim ADD claim_no VARCHAR(80), ADD settlement_amount_usdt DECIMAL(24,6)");
        jdbc.execute("CREATE TABLE nx_tradein_application(id BIGINT,user_id BIGINT,target_order_no VARCHAR(80),target_device_id BIGINT,target_product_name VARCHAR(80),completed_at DATETIME,tradein_no VARCHAR(80),source_device_id BIGINT,updated_at DATETIME,is_deleted INT)");
        jdbc.execute("CREATE TABLE nx_payment_record(id BIGINT,payment_no VARCHAR(80),order_no VARCHAR(80),user_id BIGINT,provider VARCHAR(40),is_deleted INT,payment_status VARCHAR(32),amount_usdt DECIMAL(24,6),paid_at DATETIME,created_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_wallet_bill(id BIGINT,user_id BIGINT,deleted INT,bill_no VARCHAR(80),type VARCHAR(40),token VARCHAR(16),direction VARCHAR(8),amount DECIMAL(24,6),occurred_at DATETIME)");
        jdbc.execute("CREATE TABLE nx_wallet_ledger(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT,biz_no VARCHAR(100),biz_type VARCHAR(40),asset VARCHAR(16),direction VARCHAR(8),amount DECIMAL(24,6),status VARCHAR(32),created_at DATETIME,is_deleted INT DEFAULT 0)");
        var config=new Configuration(new Environment("trial-slots",new SpringManagedTransactionFactory(),ds));
        config.addMapper(DeviceCatalogMapper.class);config.addMapper(CanonicalStateMapper.class);config.addMapper(AppTrialLifecycleMapper.class);config.addMapper(AppBundleOrderMapper.class);config.addMapper(PromotionMapper.class);
        config.addMapper(AppTradeinMapper.class);config.addMapper(AppComputeShareEnrollmentMapper.class);
        var session=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        catalog=session.getMapper(DeviceCatalogMapper.class);slots=session.getMapper(CanonicalStateMapper.class);trial=session.getMapper(AppTrialLifecycleMapper.class);bundle=session.getMapper(AppBundleOrderMapper.class);promotion=session.getMapper(PromotionMapper.class);
    }
    @BeforeEach void reset(){
        for(String t:List.of("nx_wallet_ledger","nx_wallet_bill","nx_payment_record","nx_tradein_application","nx_trial_claim","nx_user_device","nx_order_item","nx_order","nx_user","nx_product","nx_admin_device_sku","nx_promotion_reservation","nx_promotion_reward","nx_promotion_device_receipt")) jdbc.execute("DELETE FROM "+t);
        jdbc.update("INSERT INTO nx_user VALUES(7,0)");
        jdbc.update("INSERT INTO nx_product VALUES(11,'stellarbox-s1','DEVICE',1,1,'fixture GPU',8,0,'S1',1299)");
        jdbc.update("INSERT INTO nx_admin_device_sku VALUES('stellarbox-s1','120 W','fixture DC',0)");
    }
    @AfterAll void cleanup() throws Exception {
        if(admin==null)return;
        try {if(created)try(var s=admin.createStatement()){s.execute("DROP DATABASE `"+schema+"`");System.out.println("TRIAL_SLOTS_FIXTURE_DROP="+schema);}} finally {admin.close();}
    }
    void converted(){
        assertThat(trial.insertConversionOrder(7L,"TRC-FIXTURE",11L,new BigDecimal("1299"),new BigDecimal("53"),new BigDecimal("1246"))).isEqualTo(1);
        assertThat(trial.insertConversionOrderItem("TRC-FIXTURE",11L,"stellarbox-s1","S1",new BigDecimal("1299"))).isEqualTo(1);
        assertThat(trial.insertPurchasedDevice(7L,"TRC-FIXTURE",11L,"stellarbox-s1","S1","DEVICE","fixture-unit","S1",new BigDecimal("1299"))).isEqualTo(1);
        jdbc.update("INSERT INTO nx_trial_claim SELECT 1,7,id,'REDEEMED',NOW(),0,'TRIAL-FIXTURE',1246 FROM nx_user_device");
    }
    void legacy(){converted();jdbc.update("UPDATE nx_order SET order_status='PAID',activation_status='ACTIVE'");}
    @Test void newConversionUsesStandardFulfilledStateAndOccupiesOnlyOneSlot(){
        converted();
        assertThat(jdbc.queryForMap("SELECT order_status,activation_status FROM nx_order"))
          .containsEntry("order_status","COMPLETED").containsEntry("activation_status","ACTIVATED");
        assertThat(slots.activeDeviceCount(7L)).isEqualTo(1);
        assertThat(slots.reservedDeviceOrderCount(7L)).isZero();
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
        assertThat(bundle.reservedDeviceOrderCount(7L)).isZero();
        assertThat(promotion.occupiedDeviceSlots(7L)).isEqualTo(slots.activeDeviceCount(7L));
        assertThat(slots.activeDeviceCount(7L)+slots.reservedDeviceOrderCount(7L)+1).isLessThanOrEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CLAIMED","ACTIVE","GRACE","EXTENDED"})
    void everyLiveTrialReservesExactlyOneSlotWithoutCreatingAPurchasedDevice(String state){
        jdbc.update("INSERT INTO nx_trial_claim(id,user_id,status) VALUES(1,7,?)",state);
        assertThat(slots.activeDeviceCount(7L)).isZero();
        assertThat(slots.reservedTrialSlotCount(7L)).isEqualTo(1);
        assertThat(slots.lockReservedTrialSlotCount(7L)).isEqualTo(1);
        assertThat(bundle.reservedTrialSlotCount(7L)).isEqualTo(1);
        assertThat(promotion.occupiedDeviceSlots(7L)).isEqualTo(1);
        assertThat(slots.reservedTrialSlotCount(8L)).isZero();
        jdbc.update("UPDATE nx_trial_claim SET status='REDEEMED'");
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
    }

    @Test void trialReservationIsAccountScopedAndExcludesDeletedTerminalAndDevelopmentClaims(){
        jdbc.update("INSERT INTO nx_trial_claim(id,user_id,status) VALUES(1,7,'ACTIVE')");
        jdbc.update("UPDATE nx_trial_claim SET is_deleted=1");
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
        jdbc.update("UPDATE nx_trial_claim SET is_deleted=0,status='CANCELLED'");
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
        jdbc.update("UPDATE nx_trial_claim SET status='FAILED'");
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
        jdbc.update("UPDATE nx_trial_claim SET status='ACTIVE'");
        jdbc.update("UPDATE nx_user SET sandbox=1");
        assertThat(slots.reservedTrialSlotCount(7L)).isZero();
        assertThat(slots.lockReservedTrialSlotCount(7L)).isZero();
    }
    @Test void legacyFulfilledConversionUsesExactClaimDeviceAndOrderWithoutRewritingHistory(){
        legacy();
        assertThat(slots.activeDeviceCount(7L)).isEqualTo(1);
        assertThat(slots.reservedDeviceOrderCount(7L)).isZero();
        assertThat(bundle.reservedDeviceOrderCount(7L)).isZero();
        assertThat(promotion.occupiedDeviceSlots(7L)).isEqualTo(slots.activeDeviceCount(7L));
        assertThat(jdbc.queryForMap("SELECT order_status,activation_status,amount_usdt FROM nx_order"))
          .containsEntry("order_status","PAID").containsEntry("activation_status","ACTIVE");
        assertThat(jdbc.queryForObject("SELECT amount_usdt FROM nx_order",BigDecimal.class)).isEqualByComparingTo("1246");
    }
    @Test void absentOrWrongClaimDeviceProofCannotReleasePendingReservation(){
        for(String mutation:List.of("DELETE FROM nx_trial_claim","UPDATE nx_trial_claim SET status='GRACE'",
          "UPDATE nx_trial_claim SET user_id=8","UPDATE nx_trial_claim SET user_device_id=999","UPDATE nx_trial_claim SET is_deleted=1",
          "UPDATE nx_trial_claim SET settled_at=NULL","UPDATE nx_user_device SET user_id=8",
          "UPDATE nx_user_device SET source_order_no='OTHER'","UPDATE nx_user_device SET product_id=22",
          "UPDATE nx_user_device SET source_channel='ORDER'","UPDATE nx_user_device SET activated_at=NULL",
          "UPDATE nx_user_device SET is_deleted=1","UPDATE nx_user_device SET source_environment='SANDBOX'",
          "UPDATE nx_user_device SET run_id='fixture-other-run'","UPDATE nx_order SET order_type='SINGLE'","UPDATE nx_order SET order_type=NULL",
          "UPDATE nx_order SET payment_status='UNPAID'","UPDATE nx_order SET quantity=2,item_count=2",
          "UPDATE nx_order SET activation_status='WAITING_ACTIVATION'")){
            reset();legacy();jdbc.execute(mutation);
            assertThat(slots.reservedDeviceOrderCount(7L)).as(mutation).isPositive();
            assertThat(bundle.reservedDeviceOrderCount(7L)).as(mutation).isPositive();
            assertThat(promotion.occupiedDeviceSlots(7L)).as(mutation).isGreaterThan(slots.activeDeviceCount(7L));
        }
    }
    @Test void legitimatelyDeactivatedConvertedDeviceDoesNotBecomePendingAgain(){
        legacy(); Long device=trial.deviceIdByInstanceNo("fixture-unit");
        assertThat(slots.deactivateOwnedDeviceCas(7L,device,0L)).isEqualTo(1);
        assertThat(slots.activeDeviceCount(7L)).isZero();assertThat(slots.reservedDeviceOrderCount(7L)).isZero();
        assertThat(bundle.reservedDeviceOrderCount(7L)).isZero();
        assertThat(promotion.occupiedDeviceSlots(7L)).isEqualTo(slots.activeDeviceCount(7L));
    }
    @Test void ordinaryPendingOrdersAndPromotionReservationRemainCounted(){
        legacy();jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,activation_status,paid_at,created_at,updated_at,is_deleted) SELECT 7,'ORD-PENDING',11,1,'SINGLE',1,1299,0,1299,'PAID','PAID','WAITING_ACTIVATION',NOW(),NOW(),NOW(),0");
        jdbc.update("INSERT INTO nx_promotion_reservation VALUES(1,7,'DEVICE','RESERVED',1)");
        assertThat(slots.reservedDeviceOrderCount(7L)).isEqualTo(2);
        assertThat(bundle.reservedDeviceOrderCount(7L)).isEqualTo(2);
        assertThat(promotion.occupiedDeviceSlots(7L)).isEqualTo(3);
        assertThat(slots.reservedDeviceOrderCount(8L)).isZero();
    }
    @Test void developmentScopeUsesSameFulfillmentProofWithoutCrossingProductionUsers(){
        legacy();jdbc.update("UPDATE nx_user SET sandbox=1");
        assertThat(slots.activeDeviceCount(7L)).isZero();assertThat(slots.developmentActiveDeviceCount(7L)).isEqualTo(1);
        assertThat(slots.developmentReservedDeviceOrderCount(7L)).isZero();
        jdbc.update("UPDATE nx_trial_claim SET user_id=8");
        assertThat(slots.developmentReservedDeviceOrderCount(7L)).isEqualTo(1);
    }
    @Test void fulfilledLegacyMicrosecondOrderProjectsConsistentlyWithoutRewritingFinancialFacts(){
        legacy();
        jdbc.update("UPDATE nx_order SET paid_at='2026-10-16 15:52:46.123456'");
        jdbc.update("UPDATE nx_user_device SET activated_at='2026-10-16 15:52:46'");
        var row=slots.userOrdersPage(7L,null,10).get(0);
        assertThat(row.orderStatus()).isEqualTo("COMPLETED");
        assertThat(row.activationStatus()).isEqualTo("ACTIVATED");
        assertThat(row.activatedAt()).isBefore(row.paidAt());
        assertThat(catalog.findOrder("TRC-FIXTURE").state()).isEqualTo("activated");
        assertThat(catalog.findOrderFacts("TRC-FIXTURE").orderStatus()).isEqualTo("COMPLETED");
        assertThat(catalog.findOrderFacts("TRC-FIXTURE").activationStatus()).isEqualTo("ACTIVATED");
        assertThat(slots.userOrdersPage(8L,null,10)).isEmpty();
        assertThat(jdbc.queryForMap("SELECT order_status,activation_status FROM nx_order"))
          .containsEntry("order_status","PAID").containsEntry("activation_status","ACTIVE");
    }
    void charge(){jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,status,created_at) VALUES(7,'TRIAL-FIXTURE:CHARGE','TRIAL_CHARGE','USDT','OUT',1246,'POSTED',NOW())");}
    @Test void trialFundingFindsOnlyTheExactOwnedUsdtChargeForBothLegacyAndNewOrders(){
        for(boolean old:List.of(true,false)){
            reset();if(old)legacy();else converted();charge();
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,status,created_at) VALUES(7,'TRIAL-FIXTURE:NEX','TRIAL_BONUS','NEX','IN',3,'POSTED',NOW()),(7,'TRIAL-FIXTURE:REMAINDER','TRIAL_BONUS','USDT','IN',2,'POSTED',NOW())");
            var rows=catalog.listOrderFunding("TRC-FIXTURE");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).bizNo()).isEqualTo("TRIAL-FIXTURE:CHARGE");
            assertThat(rows.get(0).source()).isEqualTo("D4_LEDGER");
            assertThat(rows.get(0).amount()).isEqualByComparingTo("1246");
            assertThat(rows.get(0).direction()).isEqualTo("OUT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger",Integer.class)).isEqualTo(3);
        }
    }
    @Test void unrelatedOrUnprovenTrialChargeCannotBecomeOrderFunding(){
        for(String mutation:List.of("UPDATE nx_wallet_ledger SET user_id=8", "UPDATE nx_wallet_ledger SET biz_no='OTHER:CHARGE'",
          "UPDATE nx_wallet_ledger SET asset='NEX'","UPDATE nx_wallet_ledger SET direction='IN'", "UPDATE nx_wallet_ledger SET biz_type='OTHER'",
          "UPDATE nx_wallet_ledger SET status='FAILED'","UPDATE nx_wallet_ledger SET amount=1245","UPDATE nx_wallet_ledger SET is_deleted=1",
          "UPDATE nx_trial_claim SET user_id=8","UPDATE nx_trial_claim SET status='GRACE'","UPDATE nx_trial_claim SET user_device_id=999",
          "UPDATE nx_trial_claim SET settled_at=NULL","UPDATE nx_trial_claim SET is_deleted=1","UPDATE nx_user_device SET source_order_no='OTHER'",
          "UPDATE nx_user_device SET source_environment='SANDBOX'","UPDATE nx_user_device SET run_id='other'","UPDATE nx_user_device SET user_id=8",
          "UPDATE nx_order SET order_type='SINGLE'")){
            reset();legacy();charge();jdbc.execute(mutation);
            assertThat(catalog.listOrderFunding("TRC-FIXTURE")).as(mutation).isEmpty();
        }
        reset();converted();
        assertThat(catalog.listOrderFunding("TRC-FIXTURE")).isEmpty();
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,status,created_at) VALUES(7,'TRC-FIXTURE','ORDER_PAY','USDT','OUT',1246,'SUCCESS',NOW())");
        assertThat(catalog.listOrderFunding("TRC-FIXTURE")).hasSize(1);
    }
    @Test void unprovenLegacyActivationIsNotNormalized(){
        for(String mutation:List.of("UPDATE nx_trial_claim SET status='GRACE'","UPDATE nx_trial_claim SET user_id=8",
          "UPDATE nx_user_device SET source_environment='SANDBOX'","UPDATE nx_user_device SET activated_at=NULL",
          "UPDATE nx_order SET order_type='SINGLE'")){
            reset();legacy();jdbc.execute(mutation);
            var row=slots.userOrdersPage(7L,null,10).get(0);
            assertThat(row.orderStatus()).as(mutation).isEqualTo("PAID");
            assertThat(row.activationStatus()).as(mutation).isEqualTo("ACTIVE");
            assertThat(catalog.findOrder("TRC-FIXTURE").state()).as(mutation).isEqualTo("paid");
        }
    }

    @Test void laterRefundOrChargebackKeepsOriginalChargeTraceAndTerminalState(){
        for(String state:List.of("REFUNDED","CHARGEBACK")){
            reset();legacy();charge();
            jdbc.update("UPDATE nx_order SET payment_status=?,order_status=?,activation_status=?",state,state,state);
            jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,status,created_at) VALUES(7,'E4-REFUND-TRC-FIXTURE','ORDER_REFUND','USDT','IN',1246,'SUCCESS',NOW())");
            assertThat(catalog.listOrderFunding("TRC-FIXTURE")).extracting(r->r.bizNo())
              .containsExactlyInAnyOrder("TRIAL-FIXTURE:CHARGE","E4-REFUND-TRC-FIXTURE");
            assertThat(catalog.findOrder("TRC-FIXTURE").state()).isEqualTo(state.toLowerCase(java.util.Locale.ROOT));
            assertThat(slots.userOrdersPage(7L,null,10).get(0).orderStatus()).isEqualTo(state);
        }
    }

}
