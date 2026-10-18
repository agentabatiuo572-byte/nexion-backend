package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ffdd.opsconsole.finance.application.WithdrawalPayoutCallbackService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CregisPayoutCallbackHttpContractTest {
    @Test void onlyDurablyAcceptedVerifiedCallbackReceivesLiteralSuccess() throws Exception {
        var service = mock(WithdrawalPayoutCallbackService.class);
        var http = MockMvcBuilders.standaloneSetup(new CregisPayoutCallbackController(service)).build();
        when(service.receive(any())).thenReturn(ApiResult.ok(Map.of("status","ACCEPTED","callbackVerified",true)));
        for (String path : new String[]{"/api/cregis/callbacks/payout","/openapi/v1/withdrawals/cregis/callbacks/payout"})
            http.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(content().string("success"));
        when(service.receive(any())).thenReturn(ApiResult.fail(401,"CREGIS_CALLBACK_SIGNATURE_INVALID"));
        http.perform(post("/api/cregis/callbacks/payout").contentType("application/json").content("{}"))
                .andExpect(content().string("rejected"));
        when(service.receive(any())).thenThrow(new IllegalStateException("fixture durable write failure"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> http.perform(
                post("/api/cregis/callbacks/payout").contentType("application/json").content("{}")))
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
