package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Disposable database; provider and domain delivery are isolated fakes, real MySQL/mapper/transactions. */
@EnabledIfEnvironmentVariable(named="PAYMENT_PIN_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3334[38]/(payment_pin_backend_test|outstanding_pay_test).*")
class PurchasePaymentMySqlTest {
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc,admin;
    static String schema;
    static PurchasePaymentMapper db;
    PurchasePaymentService service;
    PurchasePaymentPolicyService policy;
    AppOrderCommandService orders;
    AppTradeinService tradeins;
    HdPayGateway gateway;
    CregisGatewayRouter router;
    CregisGateway provider;
    BscDepositProof chain;
    CregisProperties cr;
    AuditLogService audit;
    AdminIdempotencyService commands;

    @BeforeAll static void database() throws Exception {
        schema="outstanding_pay_unit_"+UUID.randomUUID().toString().replace("-","");
        String base=System.getenv("PAYMENT_PIN_TEST_DB_URL"),username=System.getenv("PAYMENT_PIN_TEST_DB_USERNAME"),password=System.getenv("PAYMENT_PIN_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,username,password));
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        source=new DriverManagerDataSource(base.replaceFirst("/(?:payment_pin_backend_test|outstanding_pay_test)","/"+schema),username,password);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE nx_user(id BIGINT PRIMARY KEY,status VARCHAR(24),sandbox TINYINT,is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_order(order_no VARCHAR(96) COLLATE utf8mb4_general_ci PRIMARY KEY,user_id BIGINT,amount_usdt DECIMAL(24,6),subtotal_usdt DECIMAL(24,6),discount_usdt DECIMAL(24,6),payment_no VARCHAR(96),payment_status VARCHAR(24),order_status VARCHAR(24),is_deleted TINYINT DEFAULT 0,updated_at DATETIME(6),created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE nx_promotion_order_receipt(order_no VARCHAR(96) PRIMARY KEY,pay_by DATETIME(6))");
        jdbc.execute("CREATE TABLE nx_order_item(id BIGINT PRIMARY KEY,order_no VARCHAR(96),product_id BIGINT,product_no VARCHAR(64),quantity INT,unit_price_usdt DECIMAL(24,6),is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE nx_payment_record(payment_no VARCHAR(96) PRIMARY KEY,order_no VARCHAR(96),user_id BIGINT,provider VARCHAR(32),provider_payment_id VARCHAR(128),amount_usdt DECIMAL(24,6),currency VARCHAR(8),payment_status VARCHAR(24),signature_status VARCHAR(32),raw_callback JSON,paid_at DATETIME(6),created_at DATETIME(6),updated_at DATETIME(6),is_deleted TINYINT)");
        jdbc.execute("CREATE TABLE nx_cregis_deposit_address(id BIGINT PRIMARY KEY,address VARCHAR(64),project_id BIGINT,chain_id VARCHAR(16),state VARCHAR(24),user_id BIGINT,allocation_block BIGINT,allocation_hash VARCHAR(66),updated_at DATETIME(6))");
        jdbc.execute("CREATE TABLE nx_cregis_provision_gate(id INT PRIMARY KEY,state VARCHAR(24),assign_enabled TINYINT,credit_enabled TINYINT,payout_enabled TINYINT,version BIGINT,switch_reason VARCHAR(128))");
        jdbc.execute("CREATE TABLE purchase_test_effect(order_no VARCHAR(96) PRIMARY KEY,deliveries INT)");
        try(Connection c=source.getConnection()){
            var resource=new org.springframework.core.io.FileSystemResource("scripts/migrations/20261031_purchase_direct_payments.sql");
            ScriptUtils.executeSqlScript(c,resource);ScriptUtils.executeSqlScript(c,resource);
            var deadlines=new org.springframework.core.io.FileSystemResource("scripts/migrations/20261101_purchase_payment_deadlines.sql");
            ScriptUtils.executeSqlScript(c,deadlines);ScriptUtils.executeSqlScript(c,deadlines);
        }
        jdbc.execute("ALTER TABLE nx_purchase_payment_arrival ADD COLUMN chain_id VARCHAR(32) NOT NULL DEFAULT '2510'");
        Configuration config=new Configuration();config.setEnvironment(new Environment("direct-pay-isolated",new SpringManagedTransactionFactory(),source));
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);config.addMapper(PurchasePaymentMapper.class);
        db=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(PurchasePaymentMapper.class);
    }
    @AfterAll static void removeOwnedDatabase(){if(schema!=null&&schema.matches("outstanding_pay_unit_[0-9a-f]{32}"))admin.execute("DROP DATABASE "+schema);}
    @BeforeEach void fixture(){
        for(String table:List.of("nx_purchase_payment_result","nx_purchase_payment_arrival","nx_purchase_payment_intent","nx_purchase_payment_quote","purchase_test_effect","nx_payment_record","nx_order_item","nx_order","nx_user","nx_cregis_deposit_address","nx_cregis_provision_gate"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO nx_cregis_provision_gate VALUES(1,'IDLE',1,1,1,0,'')");
        jdbc.update("UPDATE nx_purchase_payment_policy SET version=1,policy_json=? WHERE id=1",json(List.of(values("paymentMethod","HDPAY_BANK","configured",true,"channelFeeRatePct","0.00","channelFeeFixed","0.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"),values("paymentMethod","CREGIS_USDT_BSC","configured",true,"channelFeeRatePct","0.00","channelFeeFixed","0.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))));
        jdbc.update("INSERT INTO nx_user VALUES(7,'ACTIVE',0,0)");
        jdbc.update("INSERT INTO nx_order(order_no,user_id,amount_usdt,subtotal_usdt,discount_usdt,payment_status,order_status) VALUES('ORDER-1',7,19.995,25,5.005,'PENDING','PENDING_PAYMENT')");
        jdbc.update("INSERT INTO nx_order_item VALUES(1,'ORDER-1',18,'SKU-18',1,25,0)");
        orders=mock(AppOrderCommandService.class);tradeins=mock(AppTradeinService.class);gateway=mock(HdPayGateway.class);router=mock(CregisGatewayRouter.class);provider=mock(CregisGateway.class);chain=mock(BscDepositProof.class);audit=mock(AuditLogService.class);
        when(router.provider()).thenReturn(provider);when(router.depositCallbackUrl()).thenReturn("https://callback.example/deposit");
        var cregisPolicy=mock(CregisDepositPolicyService.class);when(cregisPolicy.current()).thenReturn(new CregisDepositPolicy(1,BigDecimal.TEN,BigDecimal.ONE,15));
        commands=mock(AdminIdempotencyService.class);
        doAnswer(i->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->((java.util.function.Supplier<?>)i.getArgument(4)).get()))
                .when(commands).executeRetained(anyString(),anyString(),anyString(),any(),any());
        policy=new PurchasePaymentPolicyService(db,cregisPolicy,commands,audit,new ObjectMapper());
        var hd=mock(HdPayProperties.class);when(hd.ready()).thenReturn(true);when(hd.getServerIp()).thenReturn("8.8.8.8");when(hd.isTrustedPaymentPage(anyString())).thenAnswer(i->i.getArgument(0,String.class).startsWith("https://pay.example/"));
        cr=new CregisProperties();cr.setMode(CregisProperties.Mode.PROVIDER);cr.setDepositEnabled(true);cr.setDepositCreditEnabled(true);cr.setProjectId(99);cr.setDepositConfirmations(15);cr.setBscRpcUrl("https://bsc.invalid");cr.setEthereumRpcUrl("https://ethereum.invalid");
        when(provider.projectCoins()).thenReturn(List.of(new CregisGateway.Coin(CregisNetwork.BEP20.currency(),"USDT-BEP20","2510",CregisNetwork.BEP20.tokenId(),true,true),
                new CregisGateway.Coin(CregisNetwork.ERC20.currency(),"USDT-ERC20","60",CregisNetwork.ERC20.tokenId(),true,true)));
        var fx=mock(VietnamPaymentMapper.class);when(fx.findFxQuoteConfig()).thenReturn(Map.of("baseRateVndPerUsdt","25000","buySpreadPct","0","version",1));
        service=new PurchasePaymentService(db,policy,orders,tradeins,mock(AppTrialLifecycleService.class),hd,gateway,cr,router,chain,fx,new DataSourceTransactionManager(source),new ObjectMapper(),audit,commands, org.mockito.Mockito.mock(ffdd.opsconsole.device.application.TestCloudActivationAdmission.class));
        when(gateway.createPayOrder(any())).thenReturn(new HdPayGateway.PayPage("https://pay.example/hosted"));
        when(orders.settleVerifiedExternal(eq(7L),eq("ORDER-1"))).thenAnswer(i->{
            VerifiedPurchasePayment.require(7, new BigDecimal("19.995"));
            jdbc.update("INSERT INTO purchase_test_effect VALUES('ORDER-1',1)");
            jdbc.update("UPDATE nx_order SET payment_status='PAID',order_status='COMPLETED' WHERE order_no='ORDER-1'");
            return ApiResult.ok(Map.of("orderNo","ORDER-1","paymentStatus","PAID","canonicalStatus","activated"));
        });
    }
    Map<String,Object> quote(String method){return service.quote(7,Map.of("kind","ORDER_PAY","paymentMethod",method,"payload",Map.of("orderNo","ORDER-1")));}
    java.time.Instant orderDeadline(){return instant(db.one("SELECT created_at FROM nx_order WHERE order_no='ORDER-1'").get("created_at")).plusSeconds(1800);}
    void time(java.time.Instant at){org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",java.time.Clock.fixed(at,java.time.ZoneOffset.UTC));}
    @Test void delayedQuoteAndIntentUseTheOriginalOrderDeadlineAndNoNewPaymentAtCutoff(){
        var deadline=orderDeadline();time(deadline.minusSeconds(60));
        var quote=quote("HDPAY_BANK");assertThat(quote).containsEntry("deadlineAt",deadline.toString()).containsEntry("expiresAt",deadline.toString()).containsEntry("confirmationGraceEndsAt",deadline.plusSeconds(300).toString());
        var pending=service.create(7,"deadline-original",text(quote.get("paymentQuoteId")));
        assertThat(pending).containsEntry("expiresAt",deadline.toString());
        time(deadline);
        assertThatThrownBy(()->service.create(7,"deadline-new-key",text(quote.get("paymentQuoteId")))).hasMessage("PURCHASE_PAYMENT_QUOTE_ALREADY_USED");
        jdbc.update("DELETE FROM nx_purchase_payment_intent");
        assertThatThrownBy(()->quote("HDPAY_BANK")).hasMessage("ORDER_PAYMENT_EXPIRED");
        assertThatThrownBy(()->service.create(7,"deadline-new-key",text(quote.get("paymentQuoteId")))).hasMessage("ORDER_PAYMENT_EXPIRED");
        verify(gateway,times(1)).createPayOrder(any());
    }
    @Test void actualOrderIdentityPreventsCasePaddingAndExtraPayloadAliases(){
        var q=service.quote(7,values("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",values("orderNo","order-1 ")));
        assertThat(parse(db.one("SELECT payload_json FROM nx_purchase_payment_quote WHERE quote_id=?",q.get("paymentQuoteId")).get("payload_json"))).containsEntry("orderNo","ORDER-1");
        var pending=service.create(7,"canonical-order-intent",text(q.get("paymentQuoteId")));
        // A pre-upgrade intent can still contain an alias: resolve it through the real order column.
        jdbc.update("UPDATE nx_purchase_payment_intent SET payload_json=JSON_OBJECT('orderNo','order-1 ') WHERE intent_no=?",pending.get("intentNo"));
        for(String alias:List.of("ORDER-1","order-1","ORDER-1 "))
            assertThatThrownBy(()->service.quote(7,Map.of("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",Map.of("orderNo",alias,"extra","second-tab")))).hasMessage("PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isEqualTo(1);verify(gateway,times(1)).createPayOrder(any());
    }
    @Test void sixHundredHistoricalAttemptsCannotDelayAnActiveConfirmationAndFailedQueriesRotate(){
        var q=quote("HDPAY_BANK");String no=text(service.create(7,"active-before-history",text(q.get("paymentQuoteId"))).get("intentNo"));
        jdbc.update("INSERT INTO nx_order(order_no,user_id,amount_usdt,payment_status,order_status) VALUES('CLOSED-HISTORY',7,0,'CANCELLED','CANCELLED')");
        var rows=new ArrayList<Object[]>();for(int i=0;i<600;i++)rows.add(new Object[]{String.format("HIST-%03d",i),"HQ-"+i,"HC-"+i,no});
        jdbc.batchUpdate("INSERT INTO nx_purchase_payment_intent(intent_no,quote_id,user_id,kind,payment_method,command_key,payload_json,quote_json,pricing_hash,status,reason,expires_at,created_at) SELECT ?,?,user_id,kind,payment_method,?,JSON_OBJECT('orderNo','CLOSED-HISTORY'),quote_json,pricing_hash,'EXPIRED','PAYMENT_TIMEOUT',expires_at,DATE_SUB(NOW(6),INTERVAL 1 DAY) FROM nx_purchase_payment_intent WHERE intent_no=?",rows);
        when(gateway.queryPayOrder(startsWith("HIST-"))).thenThrow(new IllegalStateException("HISTORICAL_QUERY_UNAVAILABLE"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"LIVE-PROVIDER",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        service.scheduled();assertThat(service.detail(no)).containsEntry("status","DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE intent_no LIKE 'HIST-%' AND observed_at IS NOT NULL",Integer.class)).isEqualTo(10);
        service.scheduled();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE intent_no LIKE 'HIST-%' AND observed_at IS NOT NULL",Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE intent_no LIKE 'HIST-%' AND reconciliation_status='QUERY_UNKNOWN'",Integer.class)).isEqualTo(20);
        verify(gateway,times(1)).queryPayOrder("HIST-000");verify(orders,times(1)).settleVerifiedExternal(7L,"ORDER-1");
    }
    @Test void oldFailedKeyRemainsStableAfterAnotherAttemptDeliveredTheOrder(){
        var deadline=orderDeadline();time(deadline.minusSeconds(60));var q=quote("HDPAY_BANK");String old=text(service.create(7,"old-failed-key",text(q.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(old)).thenReturn(new HdPayGateway.PayOrder(old,"OLD-FAILED",5,new BigDecimal("500000"),"BANKQR",null));service.get(7,old);
        var next=quote("HDPAY_BANK");String current=text(service.create(7,"new-paid-key",text(next.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(current)).thenReturn(new HdPayGateway.PayOrder(current,"CURRENT-PAID",3,new BigDecimal("500000"),"BANKQR",null));
        assertThat(service.get(7,current)).containsEntry("status","DELIVERED");time(deadline.plusSeconds(301));
        assertThat(service.get(7,old)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_FAILED");
        assertThat(service.lookup(7,"ORDER_PAY","old-failed-key")).containsEntry("intentNo",old).containsEntry("reason","PAYMENT_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='PAYMENT_TIMEOUT'",Integer.class)).isZero();
        verify(orders,never()).expireDirectPaymentOrder(anyLong(),anyString());verify(orders,times(1)).settleVerifiedExternal(7L,"ORDER-1");
    }
    @Test void reviewResultPreservesTheFullCanonicalNinetySixCharacterOrderNumber(){
        String no="Z".repeat(96);jdbc.update("UPDATE nx_order SET order_no=?",no);jdbc.update("UPDATE nx_order_item SET order_no=?",no);
        var q=service.quote(7,values("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",values("orderNo",no)));
        String intent=text(service.create(7,"long-order-number",text(q.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(intent)).thenReturn(new HdPayGateway.PayOrder(intent,"REFUNDED",4,new BigDecimal("500000"),"BANKQR",null));
        assertThat(service.get(7,intent)).containsEntry("status","MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT order_no FROM nx_purchase_payment_result WHERE intent_no=?",String.class,intent)).isEqualTo(no);
    }
    @Test void losingTheReviewCasCannotCreateAFictitiousManualNotification(){
        var q=quote("HDPAY_BANK");String no=text(service.create(7,"review-cas-lost",text(q.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"CAS-PROVIDER",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        doThrow(new IllegalStateException("TEST_AUDIT_UNAVAILABLE")).when(audit).recordRequiredForTrustedActor(any());
        PurchasePaymentMapper wrapped=mock(PurchasePaymentMapper.class,org.mockito.AdditionalAnswers.delegatesTo(db));
        doAnswer(i->{String sql=i.getArgument(0);if(sql.startsWith("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reconciliation_status='REVIEW'"))return 0;return db.write(sql,(Object[])i.getRawArguments()[1]);}).when(wrapped).write(anyString(),any(Object[].class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"db",wrapped);
        assertThat(service.get(7,no)).containsEntry("status","FUNDS_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='MANUAL_REVIEW'",Integer.class)).isZero();
        doNothing().when(audit).recordRequiredForTrustedActor(any());
        assertThat(service.get(7,no)).containsEntry("status","DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='DELIVERED'",Integer.class)).isEqualTo(1);
    }
    @Test void failedBankAttemptAllowsANewIdentityBeforeCutoffButRefundAlwaysHasAReviewReason(){
        var quote=quote("HDPAY_BANK");var pending=service.create(7,"failed-original",text(quote.get("paymentQuoteId")));String no=text(pending.get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"FAILED-PROVIDER",5,new BigDecimal(text(quote.get("amountVnd"))),"BANKQR",null));
        assertThat(service.get(7,no)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_FAILED");
        var next=quote("HDPAY_BANK");var retried=service.create(7,"failed-new-key",text(next.get("paymentQuoteId")));String retryNo=text(retried.get("intentNo"));assertThat(retryNo).isNotEqualTo(no);
        when(gateway.queryPayOrder(retryNo)).thenReturn(new HdPayGateway.PayOrder(retryNo,"REFUNDED-PROVIDER",4,new BigDecimal(text(next.get("amountVnd"))),"BANKQR",null));
        assertThat(service.get(7,retryNo)).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","PAYMENT_REFUNDED_REVIEW");
        assertThat(service.get(7,retryNo)).containsEntry("status","MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE intent_no=? AND outcome='MANUAL_REVIEW'",Integer.class,retryNo)).isEqualTo(1);verifyNoInteractions(orders);
    }
    @Test void bankGraceRequiresPaymentTimeEvidenceAndProviderExpirationIsVisible(){
        var deadline=orderDeadline();time(deadline.minusSeconds(30));var quote=quote("HDPAY_BANK");String no=text(service.create(7,"grace-bank",text(quote.get("paymentQuoteId"))).get("intentNo"));
        time(deadline.plusSeconds(299));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PAID-PROVIDER",3,new BigDecimal(text(quote.get("amountVnd"))),"BANKQR",null,deadline.minusSeconds(5),deadline.minusSeconds(6)));
        assertThat(service.get(7,no)).containsEntry("status","DELIVERED").containsEntry("expirationTime",deadline.minusSeconds(5).toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='DELIVERED'",Integer.class)).isEqualTo(1);
    }
    @Test void bankSuccessWithoutPaymentTimeAfterDeadlineCannotDeliver(){
        var deadline=orderDeadline();time(deadline.minusSeconds(30));var quote=quote("HDPAY_BANK");String no=text(service.create(7,"unknown-payment-time",text(quote.get("paymentQuoteId"))).get("intentNo"));time(deadline.plusSeconds(1));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PAID-NO-TIME",3,new BigDecimal(text(quote.get("amountVnd"))),"BANKQR",null));
        assertThat(service.get(7,no)).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","PAYMENT_TIME_UNVERIFIED");verifyNoInteractions(orders);
    }
    @Test void unknownSubmissionOnlyQueriesOriginalAndConcurrentTimeoutIsAtomicAndIdempotent() throws Exception {
        var deadline=orderDeadline();time(deadline.minusSeconds(30));var quote=quote("HDPAY_BANK");
        when(gateway.createPayOrder(any())).thenThrow(new IllegalStateException("RESPONSE_LOST"));String no=text(service.create(7,"unknown-timeout",text(quote.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(no)).thenThrow(new IllegalStateException("QUERY_UNAVAILABLE"));time(deadline.plusSeconds(299));
        assertThat(service.get(7,no)).containsEntry("status","SUBMIT_UNKNOWN");assertThat(jdbc.queryForObject("SELECT payment_status FROM nx_order",String.class)).isEqualTo("PENDING");
        time(deadline.plusSeconds(301));
        doReturn(new HdPayGateway.PayOrder(no,"ORIGINAL-PROVIDER",1,new BigDecimal(text(quote.get("amountVnd"))),"BANKQR","https://pay.example/original")).when(gateway).queryPayOrder(no);
        when(orders.expireDirectPaymentOrder(7L,"ORDER-1")).thenAnswer(i->{jdbc.update("UPDATE nx_order SET payment_status='EXPIRED',order_status='EXPIRED' WHERE order_no='ORDER-1'");return true;});
        var workers=Executors.newFixedThreadPool(4);try{var calls=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<8;i++)calls.add(workers.submit(()->service.get(7,no)));for(var call:calls)assertThat(call.get(10,TimeUnit.SECONDS)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_TIMEOUT");}finally{workers.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='PAYMENT_TIMEOUT'",Integer.class)).isEqualTo(1);
        verify(orders,times(1)).expireDirectPaymentOrder(7L,"ORDER-1");verify(gateway,times(1)).createPayOrder(any());
    }
    @Test void failedOldAttemptAndItsSuccessorProduceOnlyOneOrderTimeout(){
        var deadline=orderDeadline();time(deadline.minusSeconds(60));var firstQuote=quote("HDPAY_BANK");String first=text(service.create(7,"failed-then-timeout",text(firstQuote.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(first)).thenReturn(new HdPayGateway.PayOrder(first,"FIRST",5,new BigDecimal("500000"),"BANKQR",null));service.get(7,first);
        var nextQuote=quote("HDPAY_BANK");String next=text(service.create(7,"successor-timeout",text(nextQuote.get("paymentQuoteId"))).get("intentNo"));
        time(deadline.plusSeconds(300));when(gateway.queryPayOrder(next)).thenThrow(new IllegalStateException("QUERY_UNAVAILABLE"));
        when(orders.expireDirectPaymentOrder(7L,"ORDER-1")).thenAnswer(i->{jdbc.update("UPDATE nx_order SET payment_status='EXPIRED',order_status='EXPIRED'");return true;});
        service.get(7,first);assertThat(service.get(7,next)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_TIMEOUT").containsEntry("reconciliationStatus","QUERY_UNKNOWN");service.get(7,first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='PAYMENT_TIMEOUT'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_status FROM nx_order",String.class)).isEqualTo("EXPIRED");
        doReturn(new HdPayGateway.PayOrder(next,"LATE",3,new BigDecimal("500000"),"BANKQR",null,deadline,deadline.minusSeconds(1))).when(gateway).queryPayOrder(next);
        service.scheduled();service.scheduled();
        assertThat(service.get(7,next)).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","PAYMENT_LATE_REVIEW").containsEntry("reconciliationStatus","REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE outcome='MANUAL_REVIEW'",Integer.class)).isEqualTo(1);
        verify(orders,never()).settleVerifiedExternal(anyLong(),anyString());
    }
    @Test void timeoutReleaseFailureRollsBackIntentAndResultFact(){
        var deadline=orderDeadline();time(deadline.minusSeconds(30));var quote=quote("HDPAY_BANK");String no=text(service.create(7,"atomic-timeout",text(quote.get("paymentQuoteId"))).get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"ORIGINAL",1,new BigDecimal(text(quote.get("amountVnd"))),"BANKQR","https://pay.example/original"));
        time(deadline.plusSeconds(301));when(orders.expireDirectPaymentOrder(7L,"ORDER-1")).thenThrow(new IllegalStateException("RELEASE_FAILED"));
        assertThatThrownBy(()->service.get(7,no)).hasMessage("RELEASE_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM nx_purchase_payment_intent WHERE intent_no=?",String.class,no)).isEqualTo("AWAITING_PAYMENT");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result",Integer.class)).isZero();
    }
    @Test void unconfiguredFeesFailClosedExplicitZeroIsValidAndAnInitiatedSnapshotNeverReprices(){
        jdbc.update("UPDATE nx_purchase_payment_policy SET policy_json=? WHERE id=1",json(List.of(values("paymentMethod","HDPAY_BANK","configured",false,"channelFeeRatePct",null,"channelFeeFixed",null,"networkConfirmFeeUsdt",null,"minTopupPrincipal","10"),values("paymentMethod","CREGIS_USDT_BSC","configured",false,"channelFeeRatePct",null,"channelFeeFixed",null,"networkConfirmFeeUsdt",null,"minTopupPrincipal","10"))));
        assertThat(maps(policy.current().get("channels"))).allSatisfy(channel->{assertThat(channel).containsEntry("configured",false);assertThat(channel.get("channelFeeRatePct")).isNull();});
        assertThatThrownBy(()->quote("HDPAY_BANK")).hasMessage("PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");verifyNoInteractions(gateway);
        var configured=policy.update("explicit-free-channel",values("expectedVersion",1,"reason","Explicitly approve zero user fees", "channels",List.of(values("paymentMethod","HDPAY_BANK","channelFeeRatePct","0.00","channelFeeFixed","0.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))));
        assertThat(maps(configured.get("channels")).stream().filter(c->"CREGIS_USDT_BSC".equals(c.get("paymentMethod"))).findFirst().orElseThrow()).containsEntry("configured",false);
        var q=quote("HDPAY_BANK");assertThat(q).containsEntry("channelFeeUsdt","0.00").containsEntry("paymentFeesConfigured",true);
        var intent=service.create(7,"frozen-free-intent",text(q.get("paymentQuoteId")));String no=text(intent.get("intentNo"));
        policy.update("change-fee-after-initiation",values("expectedVersion",2,"reason","New future orders charge different fee", "channels",List.of(values("paymentMethod","HDPAY_BANK","channelFeeRatePct","5.00","channelFeeFixed","1.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-FROZEN",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        assertThat(service.get(7,no)).containsEntry("status","DELIVERED").containsEntry("channelFeeUsdt","0.00").containsEntry("totalUsdt","20.00");
    }
    @Test void feesUseDiscountedRoundedSubtotalAndNoFeeOnFee(){
        jdbc.update("UPDATE nx_purchase_payment_policy SET policy_json=? WHERE id=1",json(List.of(values("paymentMethod","HDPAY_BANK","configured",true,"channelFeeRatePct","2.50","channelFeeFixed","0.10","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"),values("paymentMethod","CREGIS_USDT_BSC","configured",true,"channelFeeRatePct","2.50","channelFeeFixed","0.10","networkConfirmFeeUsdt","0.35","minTopupPrincipal","10"))));
        var q=quote("HDPAY_BANK");assertThat(q).containsEntry("subtotalUsdt","20.00").containsEntry("businessAmountUsdt","19.995000").containsEntry("roundingAdjustmentUsdt","0.005000")
                .containsEntry("channelFeeUsdt","0.60").containsEntry("totalUsdt","20.60").containsEntry("amountVnd","515000");
        var b=quote("CREGIS_USDT_BSC");assertThat(b).containsEntry("networkConfirmFeeUsdt","0.35").containsEntry("totalUsdt","20.95");
        verifyNoInteractions(gateway,orders);verify(provider).projectCoins();verifyNoMoreInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isZero();
    }
    @Test void aLegacyUninitiatedQuoteCannotTreatMissingConfigurationAsAFreeFeePolicy(){
        var q=quote("HDPAY_BANK");
        jdbc.update("UPDATE nx_purchase_payment_quote SET quote_json=JSON_REMOVE(quote_json,'$.paymentFeesConfigured') WHERE quote_id=?",q.get("paymentQuoteId"));
        assertThatThrownBy(()->service.create(7,"legacy-unconfigured-quote",text(q.get("paymentQuoteId")))).hasMessage("PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");
        verifyNoInteractions(gateway);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isZero();
    }
    @Test void feeChangesBeforeInitiationRequireARequoteWithoutCreatingAnIntent(){
        var q=quote("HDPAY_BANK");
        policy.update("update-before-intent",values("expectedVersion",1,"reason","Changed fees before payment initiation", "channels",List.of(values("paymentMethod","HDPAY_BANK","channelFeeRatePct","5.00","channelFeeFixed","1.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))));
        assertThatThrownBy(()->service.create(7,"stale-fee-quote",text(q.get("paymentQuoteId")))).hasMessage("PURCHASE_PAYMENT_FEE_POLICY_CHANGED");
        verifyNoInteractions(gateway);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isZero();
    }
    @Test void feePublicationCannotCommitBetweenTheCheckedVersionAndIntentCommit() throws Exception {
        var q=quote("HDPAY_BANK");CountDownLatch beforeInsert=new CountDownLatch(1),releaseInsert=new CountDownLatch(1);
        PurchasePaymentMapper wrapped=mock(PurchasePaymentMapper.class,org.mockito.AdditionalAnswers.delegatesTo(db));
        doAnswer(invocation->{
            String sql=invocation.getArgument(0);Object[] args=(Object[])invocation.getRawArguments()[1];
            if(sql.startsWith("INSERT INTO nx_purchase_payment_intent")){beforeInsert.countDown();assertThat(releaseInsert.await(5,TimeUnit.SECONDS)).isTrue();}
            return db.write(sql,args);
        }).when(wrapped).write(anyString(),any(Object[].class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"db",wrapped);
        var workers=Executors.newFixedThreadPool(2);
        try{
            var create=workers.submit(()->service.create(7,"concurrent-fee-initiation",text(q.get("paymentQuoteId"))));
            assertThat(beforeInsert.await(5,TimeUnit.SECONDS)).isTrue();
            var edit=workers.submit(()->new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                return policy.update("concurrent-fee-edit",values("expectedVersion",1,"reason","Publish new fees while purchase starts", "channels",List.of(values("paymentMethod","HDPAY_BANK","channelFeeRatePct","5.00","channelFeeFixed","1.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))));
            }));
            assertThatThrownBy(()->edit.get(5,TimeUnit.SECONDS)).hasStackTraceContaining("Lock wait timeout exceeded");
            assertThat(jdbc.queryForObject("SELECT version FROM nx_purchase_payment_policy",Long.class)).isEqualTo(1L);
            releaseInsert.countDown();var intent=create.get(10,TimeUnit.SECONDS);
            assertThat(policy.update("concurrent-fee-edit",values("expectedVersion",1,"reason","Publish fees after the intent commits", "channels",List.of(values("paymentMethod","HDPAY_BANK","channelFeeRatePct","5.00","channelFeeFixed","1.00","networkConfirmFeeUsdt","0.00","minTopupPrincipal","10"))))).containsEntry("version",2L);
            assertThat(intent.get("feePolicyVersion")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isEqualTo(1);
        }finally{releaseInsert.countDown();workers.shutdownNow();}
    }
    @Test void zeroAndForeignAndCredentialPayloadFailBeforeProvider(){
        jdbc.update("UPDATE nx_order SET amount_usdt=0");
        assertThatThrownBy(()->quote("HDPAY_BANK")).hasMessage("ZERO_PAYMENT_REQUIRES_WALLET");
        assertThatThrownBy(()->service.quote(8,Map.of("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",Map.of("orderNo","ORDER-1")))).hasMessage("PURCHASE_PAYMENT_USER_UNAVAILABLE");
        assertThatThrownBy(()->service.quote(7,Map.of("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",Map.of("orderNo","ORDER-1","paymentPassword","123456")))).hasMessage("EXTERNAL_PAYMENT_CREDENTIAL_FORBIDDEN");
        verifyNoInteractions(gateway,orders);
    }
    @Test void unknownCreateCommitsFirstAndOnlyQueriesOriginalIdentityAndDeliversOnce(){
        var q=quote("HDPAY_BANK");String quoteId=text(q.get("paymentQuoteId"));
        when(gateway.createPayOrder(any())).thenAnswer(i->{
            assertThat(jdbc.queryForObject("SELECT status FROM nx_purchase_payment_intent WHERE quote_id=?",String.class,quoteId)).isEqualTo("SUBMIT_UNKNOWN");
            throw new HdPayGatewayException("TEST_PROVIDER_TIMEOUT",true);
        });
        var pending=service.create(7,"purchase-command-1",quoteId);String no=text(pending.get("intentNo"));
        assertThat(pending).containsEntry("status","SUBMIT_UNKNOWN");
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-1",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        var done=service.get(7,no);assertThat(done).containsEntry("status","DELIVERED");
        assertThat(map(done.get("receipt"))).containsEntry("walletDebitUsdt","0.00").containsEntry("paymentMethod","HDPAY_BANK")
                .containsEntry("amountUsdt","19.995000").containsEntry("paymentSubtotalUsdt","20.00").containsEntry("roundingAdjustmentUsdt","0.005000");
        service.create(7,"purchase-command-1",quoteId);service.get(7,no);
        verify(gateway,times(1)).createPayOrder(any());verify(orders,times(1)).settleVerifiedExternal(7L,"ORDER-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->service.get(8,no)).hasMessage("PURCHASE_PAYMENT_INTENT_NOT_FOUND");
    }
    @Test void confirmedDeliveryFailureRollsBackEverythingAndBecomesReview(){
        var q=quote("HDPAY_BANK");var pending=service.create(7,"purchase-failure",text(q.get("paymentQuoteId")));String no=text(pending.get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-1",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        doThrow(new IllegalStateException("TEST_AUDIT_UNAVAILABLE")).when(audit).recordRequiredForTrustedActor(any());
        var result=service.get(7,no);assertThat(result).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","TEST_AUDIT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_test_effect",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT payment_status FROM nx_order",String.class)).isEqualTo("PENDING");
    }
    @Test void twoConcurrentCreatesHaveOneDurableIntentAndOneProviderCreate() throws Exception {
        var q=quote("HDPAY_BANK");String quoteId=text(q.get("paymentQuoteId"));var workers=Executors.newFixedThreadPool(2);
        try{
            var a=workers.submit(()->service.create(7,"concurrent-command",quoteId));var b=workers.submit(()->service.create(7,"concurrent-command",quoteId));
            assertThat(a.get(10,TimeUnit.SECONDS).get("intentNo")).isEqualTo(b.get(10,TimeUnit.SECONDS).get("intentNo"));
            verify(gateway,times(1)).createPayOrder(any());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isEqualTo(1);
        }finally{workers.shutdownNow();}
    }
    @Test void shortOverAndLateFundsNeverDeliverOrCreatePaymentRecord(){
        for(String mode:List.of("short","over","late")){
            fixture();var q=quote("HDPAY_BANK");var pending=service.create(7,"amount-"+mode,text(q.get("paymentQuoteId")));String no=text(pending.get("intentNo"));
            BigDecimal expected=new BigDecimal(text(q.get("amountVnd"))),actual="short".equals(mode)?expected.subtract(BigDecimal.ONE):"over".equals(mode)?expected.add(BigDecimal.ONE):expected;
            if("late".equals(mode))jdbc.update("UPDATE nx_purchase_payment_intent SET expires_at=?",Timestamp.from(java.time.Instant.now().minusSeconds(1)));
            when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-1",3,actual,"BANKQR",null));
            assertThat(service.get(7,no)).containsEntry("status","MANUAL_REVIEW");verifyNoInteractions(orders);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record",Integer.class)).isZero();
        }
    }
    @Test void manualRevalidationJoinsCommandTransactionAndEnforcesCasAndAuditRollback(){
        var q=quote("HDPAY_BANK");var pending=service.create(7,"manual-command",text(q.get("paymentQuoteId")));String no=text(pending.get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-1",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        Map<String,Object> request=Map.of("expectedVersion",pending.get("version"),"reason","Independent funds revalidation");
        doThrow(new IllegalStateException("TEST_MANUAL_AUDIT_FAILURE")).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.revalidate(no,"manual-revalidate-key",request)).hasMessage("TEST_MANUAL_AUDIT_FAILURE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_test_effect",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_payment_record",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_purchase_payment_intent",String.class)).isEqualTo("AWAITING_PAYMENT");
        doNothing().when(audit).recordRequired(any());
        assertThat(service.revalidate(no,"manual-revalidate-key",request)).containsEntry("status","DELIVERED");
        assertThatThrownBy(()->service.revalidate(no,"manual-stale-key",request)).hasMessage("PURCHASE_PAYMENT_REVALIDATE_VERSION_CONFLICT");
    }
    @Test void bscAtomicallyDedicatesExistingPoolAddressAndNeverCallsCreate(){
        String address="0x"+"1".repeat(40),hash="0x"+"a".repeat(64);
        jdbc.update("INSERT INTO nx_cregis_deposit_address(id,address,project_id,chain_id,state,purpose) VALUES(1,?,99,'2510','UNASSIGNED','WALLET_TOPUP')",address);
        when(provider.addressBelongs("2510",address)).thenReturn(true);when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,address)).thenReturn(true);
        when(chain.zeroUsdtBalance(CregisNetwork.BEP20,address)).thenReturn(true);when(chain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(100,hash));when(chain.blockHash(CregisNetwork.BEP20,100)).thenReturn(hash);
        var q=quote("CREGIS_USDT_BSC");var pending=service.create(7,"bsc-command",text(q.get("paymentQuoteId")));
        assertThat(pending).containsEntry("address",address).containsEntry("status","AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_deposit_address",String.class)).isEqualTo("DIRECT_PURCHASE");
        assertThat(jdbc.queryForObject("SELECT purpose FROM nx_cregis_deposit_address",String.class)).isEqualTo("PURCHASE_DIRECT");
        assertThat(jdbc.queryForObject("SELECT user_id FROM nx_cregis_deposit_address",Long.class)).isNull();
        service.create(7,"bsc-command",text(q.get("paymentQuoteId")));verify(provider,never()).createAddress(anyString(),anyString(),anyString(),anyString());
        assertThatThrownBy(()->service.create(7,"bsc-second",text(quote("CREGIS_USDT_BSC").get("paymentQuoteId"))))
                .hasMessage("PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
        jdbc.update("UPDATE nx_cregis_provision_gate SET assign_enabled=0");
        assertThatThrownBy(()->quote("CREGIS_USDT_BSC")).hasMessage("CREGIS_DIRECT_PAYMENT_PAUSED");
    }
    @Test void poolProofExpiryAndCompetingTopupAllocationLeaveNoPurchaseIntent(){
        String address="0x"+"2".repeat(40),hash="0x"+"b".repeat(64);
        jdbc.update("INSERT INTO nx_cregis_deposit_address(id,address,project_id,chain_id,state,purpose) VALUES(1,?,99,'2510','UNASSIGNED','WALLET_TOPUP')",address);
        when(provider.addressBelongs("2510",address)).thenReturn(true);when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,address)).thenReturn(true);
        when(chain.zeroUsdtBalance(CregisNetwork.BEP20,address)).thenReturn(true);when(chain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(100,hash));when(chain.blockHash(CregisNetwork.BEP20,100)).thenReturn(hash);
        var q=quote("CREGIS_USDT_BSC");
        // A real topup allocator wins the candidate between provider proof and the purchase row CAS.
        when(chain.blockHash(CregisNetwork.BEP20,100)).thenAnswer(i->{jdbc.update("UPDATE nx_cregis_deposit_address SET state='READY',user_id=77 WHERE id=1");return hash;});
        assertThatThrownBy(()->service.create(7,"pool-race-command",text(q.get("paymentQuoteId")))).hasMessage("CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_deposit_address",String.class)).isEqualTo("READY");
    }
    @Test void expiredPoolProofRollsBackTheIntentAndKeepsTheAddressUnassigned() throws Exception {
        String address="0x"+"5".repeat(40),hash="0x"+"a".repeat(64);
        jdbc.update("INSERT INTO nx_cregis_deposit_address(id,address,project_id,chain_id,state,purpose) VALUES(1,?,99,'2510','UNASSIGNED','WALLET_TOPUP')",address);
        when(provider.addressBelongs("2510",address)).thenReturn(true);when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,address)).thenReturn(true);
        when(chain.zeroUsdtBalance(CregisNetwork.BEP20,address)).thenReturn(true);when(chain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(100,hash));
        CountDownLatch proven=new CountDownLatch(1);when(chain.blockHash(CregisNetwork.BEP20,100)).thenAnswer(i->{proven.countDown();return hash;});
        var q=quote("CREGIS_USDT_BSC");var worker=Executors.newSingleThreadExecutor();
        try(Connection blocker=source.getConnection()){
            blocker.setAutoCommit(false);try(Statement statement=blocker.createStatement()){statement.executeQuery("SELECT id FROM nx_user WHERE id=7 FOR UPDATE").close();}
            Future<?> attempt=worker.submit(()->service.create(7,"expired-pool-proof",text(q.get("paymentQuoteId"))));
            assertThat(proven.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(5500);blocker.commit();
            assertThatThrownBy(()->attempt.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                    .cause().hasMessage("CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_deposit_address",String.class)).isEqualTo("UNASSIGNED");
        }finally{worker.shutdownNow();}
    }
    @Test void sixDecimalBusinessAmountSurvivesTwoDecimalCollectionAndSuccessfulReceipt(){
        jdbc.update("UPDATE nx_order SET amount_usdt=80.008000,subtotal_usdt=90,discount_usdt=9.992000");
        doAnswer(i->{
            VerifiedPurchasePayment.require(7,new BigDecimal("80.008000"));
            jdbc.update("UPDATE nx_order SET payment_status='PAID',order_status='COMPLETED' WHERE order_no='ORDER-1'");
            return ApiResult.ok(Map.of("orderNo","ORDER-1","amountUsdt",new BigDecimal("80.008000"),"paymentStatus","PAID","canonicalStatus","activated"));
        }).when(orders).settleVerifiedExternal(7L,"ORDER-1");
        var q=quote("HDPAY_BANK");assertThat(q).containsEntry("businessAmountUsdt","80.008000").containsEntry("subtotalUsdt","80.01").containsEntry("roundingAdjustmentUsdt","0.002000");
        var pending=service.create(7,"rounding-80008",text(q.get("paymentQuoteId")));String no=text(pending.get("intentNo"));
        when(gateway.queryPayOrder(no)).thenReturn(new HdPayGateway.PayOrder(no,"PROVIDER-80008",3,new BigDecimal(text(q.get("amountVnd"))),"BANKQR",null));
        var delivered=service.get(7,no);assertThat(delivered).containsEntry("status","DELIVERED");
        assertThat(map(delivered.get("receipt"))).containsEntry("amountUsdt","80.008000").containsEntry("paymentSubtotalUsdt","80.01").containsEntry("roundingAdjustmentUsdt","0.002000");
        assertThat(jdbc.queryForObject("SELECT amount_usdt FROM nx_order",BigDecimal.class)).isEqualByComparingTo("80.008000");
        assertThat(jdbc.queryForObject("SELECT amount_usdt FROM nx_payment_record",BigDecimal.class)).isEqualByComparingTo("80.008000");
    }
    @Test void directProviderHistoryRoutesWithoutTopupSnapshotWalletOrGlobalRiskPause(){
        String address="0x"+"3".repeat(40),anchor="0x"+"c".repeat(64),block="0x"+"d".repeat(64),txid="0x"+"e".repeat(64);
        jdbc.update("INSERT INTO nx_cregis_deposit_address(id,address,project_id,chain_id,state,purpose) VALUES(1,?,99,'2510','UNASSIGNED','WALLET_TOPUP')",address);
        when(provider.addressBelongs("2510",address)).thenReturn(true);when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,address)).thenReturn(true);
        when(chain.zeroUsdtBalance(CregisNetwork.BEP20,address)).thenReturn(true);when(chain.head(CregisNetwork.BEP20)).thenReturn(new BscDepositProof.Head(100,anchor));when(chain.blockHash(CregisNetwork.BEP20,100)).thenReturn(anchor);
        var q=quote("CREGIS_USDT_BSC");var pending=service.create(7,"direct-history",text(q.get("paymentQuoteId")));
        BigDecimal amount=new BigDecimal(text(q.get("totalUsdt")));
        var row=new CregisGateway.DepositRow(44,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,address,amount,txid,1,105,java.time.Instant.now().getEpochSecond());
        var log=new BscDepositProof.Observation(txid,1,address,amount,105);
        when(chain.scan(CregisNetwork.BEP20,105,105,List.of(address))).thenReturn(List.of(log));
        when(chain.verifyExact(eq(CregisNetwork.BEP20),eq(txid),eq(1),eq(address),argThat(value->value!=null&&value.compareTo(amount)==0),eq(105L))).thenReturn(new BscDepositProof.TransferProof(1,105,block,15,"0x"+"4".repeat(40),address,amount,true));
        when(provider.depositsByTxid(CregisNetwork.BEP20,txid)).thenReturn(List.of(new CregisGateway.DepositTrade(44,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,address,amount,txid,1)));
        var topupDb=mock(CregisDepositMapper.class);var topupPolicies=mock(CregisDepositPolicyService.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<PurchasePaymentService> direct=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(direct.getObject()).thenReturn(service);
        var deposits=new CregisDepositService(cr,router,chain,new CregisSigner(),new ObjectMapper(),topupDb,new DataSourceTransactionManager(source),
                mock(ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository.class),mock(ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade.class),
                mock(ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.class),mock(CregisPayoutControl.class),mock(CregisExceptionSourceService.class),mock(CregisReorgResolutionService.class),topupPolicies,direct);
        try{
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(deposits,"discoverProviderRow",row);
            assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(deposits,"materializeProviderRow",row)).isTrue();
            assertThat(service.detail(text(pending.get("intentNo")))).containsEntry("status","DELIVERED");
            verifyNoInteractions(topupDb,topupPolicies);
            assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_provision_gate",String.class)).isEqualTo("IDLE");
            // Only corroborated chain reorg evidence changes an already-delivered financial record.
            when(chain.inspectCanonical(eq(CregisNetwork.BEP20),eq(txid),eq(1),eq(address),argThat(value->value!=null&&value.compareTo(amount)==0),eq(105L),eq(block),any()))
                    .thenThrow(new IllegalStateException("TEST_RPC_UNAVAILABLE"));
            assertThat(service.get(7,text(pending.get("intentNo")))).containsEntry("status","DELIVERED");
            when(chain.inspectCanonical(eq(CregisNetwork.BEP20),eq(txid),eq(1),eq(address),argThat(value->value!=null&&value.compareTo(amount)==0),eq(105L),eq(block),any()))
                    .thenReturn(new BscDepositProof.ReorgProof(true,null,130,"0x"+"f".repeat(64)));
            assertThat(service.get(7,text(pending.get("intentNo")))).containsEntry("status","MANUAL_REVIEW")
                    .containsEntry("fulfilmentStatus","DELIVERED").containsEntry("reason","PAYMENT_REORG_REVIEW");
            assertThat(jdbc.queryForObject("SELECT state FROM nx_cregis_provision_gate",String.class)).isEqualTo("BLOCKED");
            verify(orders,times(1)).settleVerifiedExternal(7L,"ORDER-1");
        }finally{deposits.closePoolWorkers();}
    }
}
