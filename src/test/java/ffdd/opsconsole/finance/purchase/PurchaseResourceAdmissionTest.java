package ffdd.opsconsole.finance.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.promotion.domain.PromotionValues.values;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import ffdd.opsconsole.finance.hdpay.HdPayGateway;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

class PurchaseResourceAdmissionTest {
    @Test void verifiedOrderDeliveryLocksParticipantsBeforeBuyerAndPricing() throws Exception {
        var db=mock(PurchasePaymentMapper.class);var admission=mock(TestCloudActivationAdmission.class);
        var gateway=mock(HdPayGateway.class);var source=mock(DataSource.class);
        var delivery=mock(Connection.class);var review=mock(Connection.class);
        when(source.getConnection()).thenReturn(delivery,review);
        when(delivery.getAutoCommit()).thenReturn(true);when(review.getAutoCommit()).thenReturn(true);
        var orders=mock(ffdd.opsconsole.commerce.application.AppOrderCommandService.class,CALLS_REAL_METHODS);
        var promotions=mock(ffdd.opsconsole.promotion.application.PromotionOrderService.class);
        var locked=new java.util.concurrent.atomic.AtomicBoolean();
        ReflectionTestUtils.setField(orders,"promotions",promotions);
        doAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            locked.set(true);return null;
        }).when(promotions).lockOrderParticipants("PAID-ORDER");
        var intent=values("intent_no","PAID-INTENT","payment_method","HDPAY_BANK","provider_reference","PROVIDER-1",
                "confirmed_at",java.time.Instant.parse("2026-11-01T00:00:00Z"),"status","FUNDS_CONFIRMED",
                "quote_json","{\"amountVnd\":\"250000\"}","kind","ORDER_PAY","user_id",7L,
                "payload_json","{\"orderNo\":\"PAID-ORDER\"}");
        when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?","PAID-INTENT")).thenReturn(intent);
        when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=? FOR UPDATE","PAID-INTENT")).thenReturn(intent);
        when(gateway.queryPayOrder("PAID-INTENT")).thenReturn(new HdPayGateway.PayOrder("PAID-INTENT","PROVIDER-1",3,new BigDecimal("250000"),"BANKQR",null));
        when(db.one("SELECT id,status,CAST(sandbox AS UNSIGNED) sandbox FROM nx_user WHERE id=? AND is_deleted=0 FOR UPDATE",7L))
                .thenAnswer(call->{
                    assertThat(locked).as("delivery must not take buyer 7 before ordered participants [3,7]").isTrue();
                    throw new BizException(409,"TEST_CHECKPOINT_BEFORE_PRICING");
                });
        var service=new PurchasePaymentService(db,null,orders,null,null,null,gateway,null,null,null,null,
                new DataSourceTransactionManager(source),new ObjectMapper(),null,null,admission,java.time.Clock.systemUTC());
        assertThatCode(()->ReflectionTestUtils.invokeMethod(service,"deliver","PAID-INTENT",false)).doesNotThrowAnyException();
        var sequence=inOrder(admission,promotions,db);sequence.verify(admission).lock();
        sequence.verify(promotions).lockOrderParticipants("PAID-ORDER");
        sequence.verify(db).one("SELECT id,status,CAST(sandbox AS UNSIGNED) sandbox FROM nx_user WHERE id=? AND is_deleted=0 FOR UPDATE",7L);
        verify(orders,never()).settleVerifiedExternal(anyLong(),anyString());
        verify(db,never()).write(contains("SET status='DELIVERED'"),any(Object[].class));
    }

    @Test void deliveryJoinsTheExistingReadCommittedParentAndItsRollback() throws Exception {
        var db=mock(PurchasePaymentMapper.class);var admission=mock(TestCloudActivationAdmission.class);
        var gateway=mock(HdPayGateway.class);var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var intent=values("intent_no","PARENT-INTENT","payment_method","HDPAY_BANK","provider_reference","PROVIDER-1",
                "confirmed_at",java.time.Instant.parse("2026-11-01T00:00:00Z"),"kind","ORDER_PAY","quote_json","{\"amountVnd\":\"250000\"}");
        when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?","PARENT-INTENT")).thenReturn(intent);
        when(gateway.queryPayOrder("PARENT-INTENT")).thenReturn(new HdPayGateway.PayOrder("PARENT-INTENT","PROVIDER-1",3,new BigDecimal("250000"),"BANKQR",null));
        doThrow(new BizException(503,"TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE")).when(admission).lock();
        var manager=new DataSourceTransactionManager(source);
        var service=new PurchasePaymentService(db,null,null,null,null,null,gateway,null,null,null,null,manager,
                new ObjectMapper(),null,null,admission,java.time.Clock.systemUTC());
        var parent=new org.springframework.transaction.support.TransactionTemplate(manager);
        parent.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(()->parent.execute(status->{ReflectionTestUtils.invokeMethod(service,"deliver","PARENT-INTENT",true);return null;}))
                .hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        verify(source,times(1)).getConnection();verify(connection).rollback();verify(connection,never()).commit();
        verify(db,never()).write(anyString(),any(Object[].class));
    }

    @Test void confirmedProviderFundsSurviveBlockedDeliveryAsTheExistingManualReviewState() throws Exception {
        var db=mock(PurchasePaymentMapper.class); var admission=mock(TestCloudActivationAdmission.class);
        var gateway=mock(HdPayGateway.class); var source=mock(DataSource.class);
        var delivery=mock(Connection.class); var review=mock(Connection.class);
        when(source.getConnection()).thenReturn(delivery,review);
        when(delivery.getAutoCommit()).thenReturn(true); when(review.getAutoCommit()).thenReturn(true);
        var before=values("intent_no","PAID-INTENT","payment_method","HDPAY_BANK","provider_reference","PROVIDER-1",
                "confirmed_at",java.time.Instant.parse("2026-10-31T00:00:00Z"),"status","FUNDS_CONFIRMED","quote_json","{\"amountVnd\":\"250000\"}");
        when(db.one("SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?","PAID-INTENT")).thenReturn(before);
        when(gateway.queryPayOrder("PAID-INTENT")).thenReturn(new HdPayGateway.PayOrder("PAID-INTENT","PROVIDER-1",3,new BigDecimal("250000"),"BANKQR",null));
        doThrow(new BizException(503,"TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE")).when(admission).lock();
        var service=new PurchasePaymentService(db,null,null,null,null,null,gateway,null,null,null,null,
                new DataSourceTransactionManager(source),new ObjectMapper(),null,null,admission,java.time.Clock.systemUTC());
        assertThatCode(()->ReflectionTestUtils.invokeMethod(service,"deliver","PAID-INTENT",false)).doesNotThrowAnyException();
        verify(delivery).rollback(); verify(delivery,never()).commit(); verify(review).commit();
        verify(db).write(contains("SET status='MANUAL_REVIEW'"),eq("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE"),eq("PAID-INTENT"));
        verify(db,never()).one(contains(" FOR UPDATE"),any(Object[].class));
        verify(db,never()).write(contains("SET status='DELIVERED'"),any(Object[].class));
        assertThat(before).containsEntry("status","FUNDS_CONFIRMED").containsKey("confirmed_at");
    }

    @Test void adminRevalidationLocksCapacityBeforeAnyIntentOrProviderGate() {
        var db=mock(PurchasePaymentMapper.class); var admission=mock(TestCloudActivationAdmission.class);
        var commands=mock(AdminIdempotencyService.class);
        when(commands.executeRetained(anyString(),anyString(),anyString(),eq(Map.class),any()))
                .thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());
        doThrow(new BizException(503,"TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE")).when(admission).lock();
        var service=new PurchasePaymentService(db,null,null,null,null,null,null,null,null,null,null,null,
                new ObjectMapper(),null,commands,admission,java.time.Clock.systemUTC());
        assertThatThrownBy(()->service.revalidate("PAID-INTENT","capacity-revalidate",Map.of("reason","review capacity after reservation","expectedVersion",0)))
                .hasMessage("TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE");
        verifyNoInteractions(db);
    }
}
