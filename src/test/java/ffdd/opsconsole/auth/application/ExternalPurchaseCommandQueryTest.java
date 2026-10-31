package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.facade.ExternalPurchaseCommandQuery;
import ffdd.opsconsole.auth.web.AppPaymentPasswordController;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.finance.purchase.ExternalPurchaseCommandQueryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ExternalPurchaseCommandQueryTest {
    @Test void deliveredReceiptWinsOverLaterManualReviewAndNeverExecutesPayment() {
        var repo=mock(PurchasePaymentMapper.class);
        when(repo.one(anyString(),eq(7L),eq("ORDER_PAY"),eq("external-key"))).thenReturn(Map.of(
                "status","MANUAL_REVIEW","receipt_json","{\"orderNo\":\"ORD-7\",\"walletDebitUsdt\":\"0\"}"));
        var result=new ExternalPurchaseCommandQueryService(repo,new ObjectMapper()).find(7L,"order-pay","external-key");
        assertThat(result).containsEntry("status","SUCCEEDED").containsEntry("canResumeSameKey",false)
                .containsEntry("receipt",Map.of("orderNo","ORD-7","walletDebitUsdt","0"));
        verify(repo,never()).write(anyString(),any(Object[].class));
    }
    @Test void activeManualExpiredAndCorruptReceiptNeverPermitWalletResume() {
        var repo=mock(PurchasePaymentMapper.class);var service=new ExternalPurchaseCommandQueryService(repo,new ObjectMapper());
        for(String state:List.of("CREATING","SUBMIT_UNKNOWN","AWAITING_PAYMENT","FUNDS_CONFIRMED","MANUAL_REVIEW")) {
            when(repo.one(anyString(),eq(7L),eq("CAPACITY_KEEP"),eq("external-key"))).thenReturn(Map.of("status",state));
            assertThat(service.find(7L,"capacity-keep","external-key")).containsEntry("status","PROCESSING").containsEntry("canResumeSameKey",false);
        }
        when(repo.one(anyString(),eq(7L),eq("CAPACITY_KEEP"),eq("external-key"))).thenReturn(Map.of("status","EXPIRED"));
        assertThat(service.find(7L,"capacity-keep","external-key")).containsEntry("status","REJECTED").containsEntry("canResumeSameKey",false);
        when(repo.one(anyString(),eq(7L),eq("CAPACITY_KEEP"),eq("external-key"))).thenReturn(Map.of("status","DELIVERED","receipt_json","bad-json"));
        assertThat(service.find(7L,"capacity-keep","external-key")).containsEntry("status","INDETERMINATE").containsEntry("canResumeSameKey",false);
    }
    @Test void everyLookupBindsAuthenticatedUserBusinessKindAndOriginalKey() {
        var repo=mock(PurchasePaymentMapper.class);var service=new ExternalPurchaseCommandQueryService(repo,new ObjectMapper());
        when(repo.one(anyString(),eq(8L),anyString(),eq("external-key"))).thenReturn(null);
        Map<String,String> kinds=Map.of("order-pay","ORDER_PAY","trial-convert","TRIAL_CONVERT","trade-in","TRADE_IN","capacity-replace","CAPACITY_REPLACE","capacity-keep","CAPACITY_KEEP");
        kinds.forEach((kind,external)->{
            assertThat(service.find(8L,kind,"external-key")).isNull();
            verify(repo).one(anyString(),eq(8L),eq(external),eq("external-key"));
        });
    }
    @Test @SuppressWarnings("unchecked") void httpQueryReturnsExternalFirstWithoutReadingPinStateOrCheckingPepper() {
        var pin=mock(AppPaymentPasswordService.class);var external=mock(ExternalPurchaseCommandQuery.class);
        var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(external);
        Map<String,Object> receipt=Map.of("kind","order-pay","commandKey","external-key","status","PROCESSING","canResumeSameKey",false);
        when(external.find(7L,"order-pay","external-key")).thenReturn(receipt);
        var auth=new UsernamePasswordAuthenticationToken("7",null,List.of());auth.setDetails(Map.of("subjectType","USER","sessionId","session-7"));
        var controller=new AppPaymentPasswordController(pin,provider);
        assertThat(controller.purchase(auth,"order-pay","external-key").getData()).isSameAs(receipt);
        verifyNoInteractions(pin);
        when(external.find(7L,"order-pay","wallet-key")).thenReturn(null);
        when(pin.query(7L,"order-pay","wallet-key")).thenReturn(Map.of("status","NOT_SEEN"));
        controller.purchase(auth,"order-pay","wallet-key");verify(pin).query(7L,"order-pay","wallet-key");
    }
}
