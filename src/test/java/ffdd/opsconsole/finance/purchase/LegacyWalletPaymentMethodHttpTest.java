package ffdd.opsconsole.finance.purchase;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.commerce.web.AppOrderCommandController;
import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.device.web.AppTradeinController;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.growth.web.AppTrialLifecycleController;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyWalletPaymentMethodHttpTest {
    @ParameterizedTest @ValueSource(strings={"HDPAY_BANK","CREGIS_USDT_BSC"})
    void aValidPinCannotMakeAnExternalMethodExecuteAnyLegacyWalletPurchase(String method) throws Exception {
        var orders=mock(AppOrderCommandService.class);var tradeins=mock(AppTradeinService.class);var trials=mock(AppTrialLifecycleService.class);
        var http=MockMvcBuilders.standaloneSetup(new AppOrderCommandController(orders),new AppTradeinController(tradeins),new AppTrialLifecycleController(trials)).build();
        var owner=new TestingAuthenticationToken("7","ignored",List.of());owner.setDetails(Map.of("subjectType","USER"));
        for(String route:List.of("/api/app/orders/ORDER-1/pay","/api/app/trade-in/submit","/api/app/trade-in/capacity-replace","/api/app/trade-in/capacity-keep","/api/trial/convert")){
            // Credential has the valid shape; body binding rejects the wrong method before the PIN/wallet service.
            String body="{\"paymentPassword\":\"018290\",\"credentialVersion\":1,\"attemptNo\":\"attempt-test\",\"paymentMethod\":\""+method+"\"}";
            http.perform(post(route).principal(owner).header("Idempotency-Key","legacy-method-test").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(orders,tradeins,trials);
    }
}
