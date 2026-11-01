package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.nio.charset.StandardCharsets;

class TestComputeV2ControllerTest {
    final TestComputeV2Service service=mock(TestComputeV2Service.class);
    final TestComputeV2Controller controller=new TestComputeV2Controller(service,new ObjectMapper());
    final String task="CV2-"+"A".repeat(32);
    final TestCloudExecutorService.Identity executor=new TestCloudExecutorService.Identity("fleet",0,0,"fleet","EM","bge-m3:567m","sha256:"+"b".repeat(64));
    @Test void duplicateUnknownAndTrailingFieldsCannotChangeTheWorkerRequest(){
        for(String body:new String[]{"{\"runner\":\"runner-1\",\"runner\":\"runner-2\"}","{\"runner\":\"runner-1\",\"userId\":7}","{\"runner\":\"runner-1\"}{}"})
            assertThatThrownBy(()->controller.next(auth(),request(body))).hasMessage("COMPUTE_V2_BODY_INVALID");
        verifyNoInteractions(service);
    }
    @Test void nonExecutorPrincipalAndNonIntegralGenerationsAreRejected(){
        assertThatThrownBy(()->controller.next(new UsernamePasswordAuthenticationToken("7",null),request("{\"runner\":\"runner-1\"}"))).hasMessage("TEST_CLOUD_AUTH_INVALID");
        for(String generation:new String[]{"1.1","-1","0","9223372036854775808"})
            assertThatThrownBy(()->controller.heartbeat(auth(),task,request("{\"runner\":\"runner-1\",\"generation\":"+generation+",\"claimToken\":\"x\"}"))).hasMessage("COMPUTE_V2_BODY_INVALID");
        verifyNoInteractions(service);
    }
    @Test void oversizedResultIsRejectedBeforeAnyServiceOrModelCall(){
        assertThatThrownBy(()->controller.complete(auth(),task,request("x".repeat(20001)))).hasMessage("COMPUTE_V2_BODY_TOO_LARGE");verifyNoInteractions(service);
    }
    @Test void onlyExactV2TaskIdentityCanAcknowledgeExit(){
        assertThatThrownBy(()->controller.exited(auth(),"CTA-legacy",request("{\"runner\":\"runner-1\",\"generation\":1,\"claimToken\":\"x\"}"))).hasMessage("COMPUTE_V2_BODY_INVALID");verifyNoInteractions(service);
    }
    UsernamePasswordAuthenticationToken auth(){return new UsernamePasswordAuthenticationToken(executor,null);}
    MockHttpServletRequest request(String json){var request=new MockHttpServletRequest();request.setContent(json.getBytes(StandardCharsets.UTF_8));return request;}
}
