package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.content.businessnotification.*;
import ffdd.opsconsole.content.businessnotification.mapper.BusinessNotificationMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual deadline, order inventory release, notification projection, and MySQL transaction path. */
@EnabledIfEnvironmentVariable(named="PAYMENT_PIN_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3334[38]/(payment_pin_backend_test|outstanding_pay_test).*")
class PurchasePaymentDeadlineMySqlTest {
    static PurchasePaymentHttpMySqlTest.Fixture f;
    static final Instant START=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(3600);
    static int sequence;
    HdPayGateway gateway;
    @BeforeAll static void setup() throws Exception {f=new PurchasePaymentHttpMySqlTest.Fixture();f.install();f.services();f.seed();}
    @AfterAll static void cleanup(){if(f!=null)f.close();}
    @BeforeEach void clockAndProvider(){
        gateway=mock(HdPayGateway.class);ReflectionTestUtils.setField(f.payments,"hdGateway",gateway);time(START.plusSeconds(60));
        when(gateway.createPayOrder(any())).thenReturn(new HdPayGateway.PayPage("https://pay.synthetic.invalid/pay"));
        when(gateway.queryPayOrder(anyString())).thenAnswer(i->new HdPayGateway.PayOrder(i.getArgument(0),"PROVIDER-"+i.getArgument(0),1,new BigDecimal("500000"),"BANKQR","https://pay.synthetic.invalid/pay"));
    }
    void time(Instant now){ReflectionTestUtils.setField(f.payments,"clock",Clock.fixed(now,ZoneOffset.UTC));}
    String order(boolean reserved){
        String no="DEADLINE-"+(++sequence);
        f.jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,activation_status,inventory_reserved,created_at) VALUES(7,?,18,1,'SINGLE',1,25,5.005,19.995,'PENDING','PENDING_PAYMENT','WAITING_PAYMENT',?,?)",no,reserved?1:0,LocalDateTime.ofInstant(START,ZoneId.of("Asia/Shanghai")));
        f.jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_no,product_name,quantity,unit_price_usdt,line_amount_usdt,sort_order) VALUES(?,18,'HTTP-SKU','Synthetic GPU',1,25,19.995,0)",no);
        return no;
    }
    Map<String,Object> start(String no){var q=f.payments.quote(7,Map.of("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",Map.of("orderNo",no)));return f.payments.create(7,"pay-"+no,q.get("paymentQuoteId").toString());}
    String status(String no){return f.jdbc.queryForObject("SELECT order_status FROM nx_order WHERE order_no=?",String.class,no);}
    void scan(){new TransactionTemplate(f.tx).execute(status->{new BusinessNotificationService(f.mapper(BusinessNotificationMapper.class)).scan();return null;});}
    @Test void automaticExpiryCannotCloseAnActivePaymentAndTimeoutRestoresOnlyRealReservation(){
        for(boolean reserved:List.of(false,true)){
            int stock=f.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class);
            if(reserved)f.jdbc.update("UPDATE nx_product SET stock=stock-1,sold_count=sold_count+1 WHERE id=18");
            String no=order(reserved);var pending=start(no);String intent=pending.get("intentNo").toString();
            if(reserved)f.jdbc.update("UPDATE nx_user SET status='FROZEN' WHERE id=7");
            assertThat(f.mapper(AppOrderCommandMapper.class).expiredPendingOrders(30,100)).noneSatisfy(row->assertThat(row.orderNo()).isEqualTo(no));
            assertThat(f.orders.expirePendingOrder(7L,no)).isFalse();time(START.plusSeconds(1800));
            assertThat(f.payments.get(7,intent)).containsEntry("status","AWAITING_PAYMENT");assertThat(status(no)).isEqualTo("PENDING_PAYMENT");
            time(START.plusSeconds(2100));assertThat(f.payments.get(7,intent)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_TIMEOUT");assertThat(status(no)).isEqualTo("EXPIRED");
            assertThat(f.jdbc.queryForObject("SELECT close_reason_code FROM nx_order WHERE order_no=?",String.class,no)).isEqualTo("PAYMENT_TIMEOUT");
            assertThat(f.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class)).isEqualTo(stock);
            f.payments.get(7,intent);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_result WHERE intent_no=? AND outcome='PAYMENT_TIMEOUT'",Integer.class,intent)).isEqualTo(1);
            if(reserved){assertThat(f.jdbc.queryForObject("SELECT status FROM nx_user WHERE id=7",String.class)).isEqualTo("FROZEN");f.jdbc.update("UPDATE nx_user SET status='ACTIVE' WHERE id=7");}
            time(START.plusSeconds(60));
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=7",Integer.class)).isZero();
    }
    @Test void genericTtlChangesCannotCloseAFailedRetryableDirectOrderAndMigrationAlignsLegacyExpiry() throws Exception {
        String no=order(false);var pending=start(no);String intent=pending.get("intentNo").toString();
        when(gateway.queryPayOrder(intent)).thenReturn(new HdPayGateway.PayOrder(intent,"FAILED",5,new BigDecimal("500000"),"BANKQR",null));
        assertThat(f.payments.get(7,intent)).containsEntry("reason","PAYMENT_FAILED");
        Object target=org.springframework.test.util.AopTestUtils.getTargetObject(f.orders);
        for(int ttl:List.of(10,90)){
            ReflectionTestUtils.setField(target,"pendingOrderTtlMinutes",ttl);
            assertThat(f.mapper(AppOrderCommandMapper.class).expiredPendingOrders(ttl,100)).noneSatisfy(row->assertThat(row.orderNo()).isEqualTo(no));
            assertThat(f.orders.expirePendingOrder(7L,no)).isFalse();assertThat(status(no)).isEqualTo("PENDING_PAYMENT");
        }
        f.jdbc.update("UPDATE nx_purchase_payment_intent SET expires_at=? WHERE intent_no=?",java.sql.Timestamp.from(START.plusSeconds(900)),intent);
        f.migrate("20261101_purchase_payment_deadlines.sql");f.migrate("20261101_purchase_payment_deadlines.sql");
        assertThat(f.payments.detail(intent)).containsEntry("deadlineAt",START.plusSeconds(1800).toString());
        String closed=order(false);f.jdbc.update("UPDATE nx_order SET payment_status='CANCELLED',order_status='CANCELLED' WHERE order_no=?",closed);
        var history=new ArrayList<Object[]>();for(int i=0;i<600;i++)history.add(new Object[]{"FAILED-HISTORY-"+i,"FH-Q-"+i,"FH-C-"+i,closed,intent});
        f.jdbc.batchUpdate("INSERT INTO nx_purchase_payment_intent(intent_no,quote_id,user_id,kind,payment_method,command_key,payload_json,quote_json,pricing_hash,status,reason,expires_at,created_at) SELECT ?,?,user_id,kind,payment_method,?,JSON_OBJECT('orderNo',?),quote_json,pricing_hash,'EXPIRED','PAYMENT_TIMEOUT',expires_at,DATE_SUB(NOW(6),INTERVAL 1 DAY) FROM nx_purchase_payment_intent WHERE intent_no=?",history);
        time(START.plusSeconds(2100));f.payments.scheduled();
        assertThat(f.payments.detail(intent)).containsEntry("status","EXPIRED").containsEntry("reason","PAYMENT_TIMEOUT");assertThat(status(no)).isEqualTo("EXPIRED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE intent_no LIKE 'FAILED-HISTORY-%' AND observed_at IS NOT NULL",Integer.class)).isEqualTo(10);
        ReflectionTestUtils.setField(target,"pendingOrderTtlMinutes",30);
    }
    @Test void verifiedBeforeDeadlineFundsDeliverDuringGraceWithoutWalletDebitAndQueueOneNotification(){
        String no=order(false);var pending=start(no);String intent=pending.get("intentNo").toString();time(START.plusSeconds(2099));
        when(gateway.queryPayOrder(intent)).thenReturn(new HdPayGateway.PayOrder(intent,"PROVIDER-"+intent,3,new BigDecimal("500000"),"BANKQR",null,START.plusSeconds(1800),START.plusSeconds(1799)));
        assertThat(f.payments.get(7,intent)).containsEntry("status","DELIVERED");assertThat(status(no)).isEqualTo("COMPLETED");
        f.payments.get(7,intent);f.payments.get(7,intent);scan();scan();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no=?",Integer.class,no)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=7",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_notification WHERE cta_href=? AND push_status='QUEUED'",Integer.class,"/pages/store/order-detail?id="+no)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key=?",Integer.class,"PURCHASE:INTENT:"+intent+":DELIVERED")).isEqualTo(1);
        when(gateway.queryPayOrder(intent)).thenReturn(new HdPayGateway.PayOrder(intent,"PROVIDER-"+intent,4,new BigDecimal("500000"),"BANKQR",null));
        assertThat(f.payments.get(7,intent)).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","PAYMENT_REFUNDED_REVIEW");f.payments.get(7,intent);scan();scan();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_business_notification_fact WHERE fact_key=?",Integer.class,"PURCHASE:INTENT:"+intent+":MANUAL_REVIEW")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE source_order_no=?",Integer.class,no)).isEqualTo(1);
    }
}
