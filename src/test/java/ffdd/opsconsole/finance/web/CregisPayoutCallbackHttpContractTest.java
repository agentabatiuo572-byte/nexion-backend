package ffdd.opsconsole.finance.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ffdd.opsconsole.finance.application.WithdrawalPayoutCallbackService;
import ffdd.opsconsole.emergency.web.DeploymentForwardedHeadersConfiguration;
import ffdd.opsconsole.emergency.web.GeoBlockEnforcementProperties;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CregisPayoutCallbackHttpContractTest {
    @Test void onlyDurablyAcceptedVerifiedCallbackReceivesLiteralSuccess() throws Exception {
        var service = mock(WithdrawalPayoutCallbackService.class);
        var http = MockMvcBuilders.standaloneSetup(new CregisPayoutCallbackController(service)).build();
        when(service.receive(any(), anyString())).thenReturn(ApiResult.ok(Map.of("status","ACCEPTED","callbackVerified",true)));
        for (String path : new String[]{"/api/cregis/callbacks/payout","/openapi/v1/withdrawals/cregis/callbacks/payout"})
            http.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(content().string("success"));
        when(service.receive(any(), anyString())).thenReturn(ApiResult.fail(401,"CREGIS_CALLBACK_SIGNATURE_INVALID"));
        http.perform(post("/api/cregis/callbacks/payout").contentType("application/json").content("{}"))
                .andExpect(content().string("rejected"));
        when(service.receive(any(), anyString())).thenThrow(new IllegalStateException("fixture durable write failure"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> http.perform(
                post("/api/cregis/callbacks/payout").contentType("application/json").content("{}")))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test void controllerUsesServletPeerInsteadOfRawForwardingHeaders() throws Exception {
        var service = mock(WithdrawalPayoutCallbackService.class);
        var http = MockMvcBuilders.standaloneSetup(new CregisPayoutCallbackController(service)).build();
        when(service.receive(any(), eq("192.0.2.5")))
                .thenReturn(ApiResult.fail(401, "CREGIS_CALLBACK_INVALID_SOURCE_IP"));

        http.perform(post("/api/cregis/callbacks/payout").contentType("application/json").content("{}")
                .with(request -> { request.setRemoteAddr("192.0.2.5"); return request; })
                .header("X-Forwarded-For", "18.143.53.174")
                .header("X-Real-IP", "18.143.53.174")
                .header("Forwarded", "for=18.143.53.174"))
                .andExpect(content().string("rejected"));
        verify(service).receive(Map.of(), "192.0.2.5");
    }

    @Test void trustedIngressPassesValidatedSourceAndUntrustedPeerCannotSpoofIt() throws Exception {
        var service = mock(WithdrawalPayoutCallbackService.class);
        var forwarding = new DeploymentForwardedHeadersConfiguration()
                .forwardedHeaderFilter(new GeoBlockEnforcementProperties());
        var http = MockMvcBuilders.standaloneSetup(new CregisPayoutCallbackController(service))
                .addFilters(forwarding).build();
        when(service.receive(any(), eq("18.143.53.174")))
                .thenReturn(ApiResult.ok(Map.of("status", "ACCEPTED")));

        for (String path : new String[]{"/api/cregis/callbacks/payout", "/openapi/v1/withdrawals/cregis/callbacks/payout"}) {
            http.perform(post(path).contentType("application/json").content("{}")
                    .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                    .header("X-Forwarded-For", "18.143.53.174"))
                    .andExpect(content().string("success"));
        }
        verify(service, times(2)).receive(Map.of(), "18.143.53.174");
        clearInvocations(service);
        http.perform(post("/api/cregis/callbacks/payout").contentType("application/json").content("{}")
                .with(request -> { request.setRemoteAddr("192.0.2.5"); return request; })
                .header("X-Forwarded-For", "18.143.53.174"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
