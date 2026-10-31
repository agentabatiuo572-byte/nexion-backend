package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.values;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.promotion.application.PromotionOrderService;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

class PurchasePaymentExpiryLockOrderTest {
    static final Instant CREATED=Instant.parse("2026-11-01T00:00:00Z");
    final PurchasePaymentMapper db=mock(PurchasePaymentMapper.class);
    final AppOrderCommandService orders=mock(AppOrderCommandService.class,CALLS_REAL_METHODS);
    final PromotionOrderService promotions=mock(PromotionOrderService.class);
    final Clock clock=mock(Clock.class);
    final AtomicBoolean participantsLocked=new AtomicBoolean();
    final Map<String,Object> row=values("intent_no","EXPIRE-INTENT","user_id",7L,"kind","ORDER_PAY",
            "payment_method","HDPAY_BANK","payload_json","{\"orderNo\":\"EXPIRE-ORDER\"}",
            "status","AWAITING_PAYMENT","expires_at",CREATED.plusSeconds(1800));
    PurchasePaymentService service;
    Connection connection;

    @BeforeEach void fixture() throws Exception {
        var source=mock(DataSource.class);connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        ReflectionTestUtils.setField(orders,"promotions",promotions);
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .as("successor reads after an owner wait must use READ_COMMITTED").isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            participantsLocked.set(true);return null;
        }).when(promotions).lockOrderParticipants("EXPIRE-ORDER");
        doReturn(true).when(orders).expireDirectPaymentOrder(7L,"EXPIRE-ORDER");
        when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=? FOR UPDATE","EXPIRE-INTENT")).thenReturn(row);
        when(db.one("SELECT id FROM nx_user WHERE id=? FOR UPDATE",7L)).thenAnswer(call->{
            assertThat(participantsLocked).as("participants [3,7] must be locked before buyer 7; otherwise issuer 3->7 and expiry 7->3 deadlock")
                    .isTrue();return Map.of("id",7L);
        });
        when(db.one("SELECT order_no,created_at,payment_status,order_status FROM nx_order WHERE order_no=? AND user_id=?","EXPIRE-ORDER",7L))
                .thenReturn(values("order_no","EXPIRE-ORDER","created_at",CREATED,"payment_status","PENDING","order_status","PENDING_PAYMENT"));
        when(db.one("SELECT pay_by FROM nx_promotion_order_receipt WHERE order_no=?","EXPIRE-ORDER")).thenReturn(null);
        when(db.write(anyString(),any(Object[].class))).thenReturn(1);
        when(clock.instant()).thenReturn(CREATED.plusSeconds(2100));
        service=new PurchasePaymentService(db,null,orders,null,null,null,null,null,null,null,null,
                new DataSourceTransactionManager(source),new ObjectMapper(),null,null,mock(TestCloudActivationAdmission.class),
                clock);
    }
    void expire(){ReflectionTestUtils.invokeMethod(service,"expire",row);}

    @Test void timeoutLocksTheOrderedParticipantsBeforeTheBuyerAndKeepsTheExistingExpiryTransaction() throws Exception {
        expire();
        var sequence=inOrder(promotions,db,orders);
        sequence.verify(db).one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=? FOR UPDATE","EXPIRE-INTENT");
        sequence.verify(promotions).lockOrderParticipants("EXPIRE-ORDER");
        sequence.verify(db).one("SELECT id FROM nx_user WHERE id=? FOR UPDATE",7L);
        sequence.verify(db).write(contains("SET status='EXPIRED',reason='PAYMENT_TIMEOUT'"),any(Object[].class));
        sequence.verify(orders).expireDirectPaymentOrder(7L,"EXPIRE-ORDER");
        verify(connection).commit();verify(connection,never()).rollback();
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }
    @Test void aSuccessorIntentStillPreventsClosingTheOriginalOrder() {
        when(db.rows(contains("SELECT p.intent_no"),any(Object[].class))).thenReturn(List.of(Map.of("intent_no","SUCCESSOR")));
        expire();verify(db,never()).write(anyString(),any(Object[].class));
        verify(orders,never()).expireDirectPaymentOrder(anyLong(),anyString());
    }
    @Test void confirmedFundsRemainUntouchedBeforeAnyOwnerOrParticipantLock() {
        row.put("confirmed_at",CREATED.plusSeconds(1700));expire();
        verifyNoInteractions(promotions);verify(db,never()).one(contains("FROM nx_user"),any(Object[].class));
        verify(db,never()).write(anyString(),any(Object[].class));
    }
    @Test void theOriginalDeadlineAndGraceAreStillRechecked() {
        when(clock.instant()).thenReturn(CREATED.plusSeconds(2099));
        expire();verify(db,never()).write(anyString(),any(Object[].class));
        verify(orders,never()).expireDirectPaymentOrder(anyLong(),anyString());
    }
}
