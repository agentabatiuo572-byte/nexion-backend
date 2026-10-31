package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual order/create services and MySQL; reuses the owned full-schema fixture, no HTTP server or vendor traffic. */
@EnabledIfEnvironmentVariable(named="PAYMENT_PIN_TEST_DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3334[38]/(payment_pin_backend_test|outstanding_pay_test).*")
class PurchasePaymentCancellationMySqlTest {
    static PurchasePaymentHttpMySqlTest.Fixture f;
    static int sequence;
    @BeforeAll static void setup() throws Exception {f=new PurchasePaymentHttpMySqlTest.Fixture();f.install();f.services();f.seed();}
    @AfterAll static void cleanup(){if(f!=null)f.close();}
    String order(){
        String no="CANCEL-IT-"+(++sequence);
        f.jdbc.update("INSERT INTO nx_order(user_id,order_no,product_id,quantity,order_type,item_count,subtotal_usdt,discount_usdt,amount_usdt,payment_status,order_status,activation_status,inventory_reserved) VALUES(7,?,18,1,'SINGLE',1,25,5.005,19.995,'PENDING','PENDING_PAYMENT','WAITING_PAYMENT',0)",no);
        f.jdbc.update("INSERT INTO nx_order_item(order_no,product_id,product_no,product_name,quantity,unit_price_usdt,line_amount_usdt,sort_order) VALUES(?,18,'HTTP-SKU','Synthetic GPU',1,25,19.995,0)",no);
        return no;
    }
    Map<String,Object> quote(String no){return f.payments.quote(7,Map.of("kind","ORDER_PAY","paymentMethod","HDPAY_BANK","payload",Map.of("orderNo",no)));}
    String quoteId(Map<String,Object> quote){return quote.get("paymentQuoteId").toString();}
    TransactionTemplate transaction(int isolation){var tx=new TransactionTemplate(f.tx);tx.setIsolationLevel(isolation);return tx;}
    void unchanged(String no){
        assertThat(f.jdbc.queryForObject("SELECT payment_status FROM nx_order WHERE order_no=?",String.class,no)).isEqualTo("PENDING");
        assertThat(f.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class)).isEqualTo(10);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=7",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_ledger WHERE user_id=7",Integer.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"CREATING","SUBMIT_UNKNOWN","AWAITING_PAYMENT","FUNDS_CONFIRMED","MANUAL_REVIEW"})
    void everyActiveRailBlocksCancellationAcrossDifferentPaymentKeys(String state){
        for(String rail:List.of("HDPAY_BANK","CREGIS_USDT_BSC","CREGIS_USDT_ERC20")){
            String no=order();var pending=f.payments.create(7,"other-tab-"+no,quoteId(quote(no)));
            f.jdbc.update("UPDATE nx_purchase_payment_intent SET payment_method=?,status=? WHERE intent_no=?",rail,state,pending.get("intentNo"));
            assertThatThrownBy(()->f.orders.cancel(7L,no,"cancel-"+no)).as(rail+" "+state)
                    .isInstanceOfSatisfying(ffdd.opsconsole.shared.exception.BizException.class,error->assertThat(error.getCode()).isEqualTo(409))
                    .hasMessage("ORDER_EXTERNAL_PAYMENT_IN_PROGRESS");unchanged(no);
        }
    }
    @Test void cancellationFirstPreventsLateCreateEvenWithAnEarlierRepeatableReadSnapshot(){
        String no=order();var q=quote(no);int creates=f.provider.creates.get();
        transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->{
            assertThat(f.jdbc.queryForObject("SELECT payment_status FROM nx_order WHERE order_no=?",String.class,no)).isEqualTo("PENDING");
            var workers=Executors.newSingleThreadExecutor();try{
                assertThat(workers.submit(()->f.orders.cancel(7L,no,"cancel-"+no)).get(10,TimeUnit.SECONDS).getCode()).isZero();
            }catch(Exception failure){throw new AssertionError(failure);}finally{workers.shutdownNow();}
            assertThatThrownBy(()->f.payments.create(7,"late-"+no,quoteId(q))).hasMessage("ORDER_NOT_PAYABLE");
            status.setRollbackOnly();
            return null;
        });
        assertThat(f.provider.creates.get()).isEqualTo(creates);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE quote_id=?",Integer.class,quoteId(q))).isZero();
        assertThat(f.orders.cancel(7L,no,"cancel-replay-"+no).getData()).containsEntry("idempotent",true);
    }
    @ParameterizedTest @ValueSource(ints={TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_DEFAULT})
    void outerRepeatableReadOrUnspecifiedCancellationFailsClosedBeforeAnyBusinessWrite(int isolation){
        String no=order();
        transaction(isolation).execute(status->{
            assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            var result=f.orders.cancel(7L,no,"rr-cancel-"+no);
            assertThat(result.getCode()).isEqualTo(503);assertThat(result.getMessage()).isEqualTo("ORDER_CANCEL_ISOLATION_UNAVAILABLE");return null;
        });
        unchanged(no);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_idempotency_record WHERE idempotency_key=?",Integer.class,"rr-cancel-"+no)).isZero();
    }
    @Test void createHoldsTheSameUserMutexAndCancelSeesItsCommittedIntent() throws Exception {
        String no=order();var q=quote(no);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var original=f.mapper(PurchasePaymentMapper.class);var wrapped=mock(PurchasePaymentMapper.class,AdditionalAnswers.delegatesTo(original));
        doAnswer(invocation->{
            String sql=invocation.getArgument(0);Object[] args=(Object[])invocation.getRawArguments()[1];
            if(sql.startsWith("INSERT INTO nx_purchase_payment_intent")){entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
            return original.write(sql,args);
        }).when(wrapped).write(anyString(),any(Object[].class));
        ReflectionTestUtils.setField(f.payments,"db",wrapped);var workers=Executors.newFixedThreadPool(2);
        try{
            var create=workers.submit(()->f.payments.create(7,"create-first-"+no,quoteId(q)));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var blockedCancel=workers.submit(()->transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status->{
                f.jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");return f.orders.cancel(7L,no,"cancel-"+no);
            }));
            assertThatThrownBy(()->blockedCancel.get(5,TimeUnit.SECONDS)).hasStackTraceContaining("Lock wait timeout exceeded");
            release.countDown();assertThat(create.get(10,TimeUnit.SECONDS)).containsEntry("status","SUBMIT_UNKNOWN");
            assertThatThrownBy(()->f.orders.cancel(7L,no,"cancel-"+no)).hasMessage("ORDER_EXTERNAL_PAYMENT_IN_PROGRESS");unchanged(no);
        }finally{release.countDown();workers.shutdownNow();ReflectionTestUtils.setField(f.payments,"db",original);}
    }
    @Test void cancelHoldsTheSameUserMutexAndLateCreateCannotIssueProviderCollection() throws Exception {
        String no=order();var q=quote(no);int creates=f.provider.creates.get();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var original=f.mapper(AppOrderCommandMapper.class);var wrapped=mock(AppOrderCommandMapper.class,AdditionalAnswers.delegatesTo(original));
        doAnswer(invocation->{
            assertThat(f.jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
            int changed=original.cancelOrder(invocation.getArgument(0),invocation.getArgument(1));
            entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return changed;
        }).when(wrapped).cancelOrder(eq(no),eq(7L));
        Object target=AopTestUtils.getTargetObject(f.orders);ReflectionTestUtils.setField(target,"mapper",wrapped);var workers=Executors.newFixedThreadPool(2);
        try{
            var cancel=workers.submit(()->f.orders.cancel(7L,no,"cancel-first-"+no));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var blockedCreate=workers.submit(()->transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute(status->{
                f.jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");return f.payments.create(7,"late-create-"+no,quoteId(q));
            }));
            assertThatThrownBy(()->blockedCreate.get(5,TimeUnit.SECONDS)).hasStackTraceContaining("Lock wait timeout exceeded");
            release.countDown();assertThat(cancel.get(10,TimeUnit.SECONDS).getCode()).isZero();
            assertThatThrownBy(()->f.payments.create(7,"late-create-"+no,quoteId(q))).hasMessage("ORDER_NOT_PAYABLE");
            assertThat(f.provider.creates.get()).isEqualTo(creates);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_purchase_payment_intent WHERE quote_id=?",Integer.class,quoteId(q))).isZero();
        }finally{release.countDown();workers.shutdownNow();ReflectionTestUtils.setField(target,"mapper",original);}
    }
    @Test void expiredIntentAndOtherOrderDoNotExpandTheExistingCancellationFence(){
        String no=order(),other=order();var q=quote(other);var intent=f.payments.create(7,"other-"+other,quoteId(q));
        assertThat(f.orders.cancel(7L,no,"cancel-"+no).getCode()).isZero();
        f.jdbc.update("UPDATE nx_purchase_payment_intent SET status='EXPIRED' WHERE quote_id=?",quoteId(q));
        assertThat(f.orders.cancel(7L,other,"cancel-"+other).getCode()).isZero();
        assertThat(f.jdbc.queryForObject("SELECT stock FROM nx_product WHERE id=18",Integer.class)).isEqualTo(10);
        f.jdbc.update("UPDATE nx_purchase_payment_intent SET expires_at=? WHERE quote_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),quoteId(q));
        f.provider.paid.add(intent.get("intentNo").toString());
        assertThat(f.payments.get(7,intent.get("intentNo").toString())).containsEntry("status","MANUAL_REVIEW").containsEntry("reason","PAYMENT_TIME_UNVERIFIED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_user_device WHERE user_id=7",Integer.class)).isZero();
    }
    @Test void aTemporaryActiveIntentRejectionRollsBackAndReclaimsTheExactOriginalCancelKey(){
        String no=order(),key="same-cancel-"+no;var q=quote(no);f.payments.create(7,"different-pay-"+no,quoteId(q));
        assertThatThrownBy(()->f.orders.cancel(7L,no,key)).hasMessage("ORDER_EXTERNAL_PAYMENT_IN_PROGRESS");unchanged(no);
        var command=f.jdbc.queryForMap("SELECT id,status,response_json FROM nx_admin_idempotency_record WHERE scope='APP:ORDER_CANCEL:USER:7' AND idempotency_key=?",key);
        assertThat(command).containsEntry("status","FAILED").containsEntry("response_json",null);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='APP_ORDER_CANCELLED' AND resource_id=?",Integer.class,no)).isZero();
        f.jdbc.update("UPDATE nx_purchase_payment_intent SET status='EXPIRED' WHERE quote_id=?",quoteId(q));
        assertThat(f.orders.cancel(7L,no,key).getCode()).isZero();
        assertThat(f.jdbc.queryForMap("SELECT id,status FROM nx_admin_idempotency_record WHERE idempotency_key=?",key)).containsEntry("id",command.get("id")).containsEntry("status","SUCCEEDED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='APP_ORDER_CANCELLED' AND resource_id=?",Integer.class,no)).isEqualTo(1);
    }
}
