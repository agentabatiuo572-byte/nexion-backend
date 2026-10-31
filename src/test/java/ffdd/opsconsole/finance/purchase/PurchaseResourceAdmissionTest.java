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
