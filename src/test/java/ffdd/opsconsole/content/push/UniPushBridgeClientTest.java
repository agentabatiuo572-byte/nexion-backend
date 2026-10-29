package ffdd.opsconsole.content.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class UniPushBridgeClientTest {
    final MockEnvironment env = new MockEnvironment();
    final HttpClient http = mock(HttpClient.class);
    final UniPushBridgeClient provider = new UniPushBridgeClient(new ObjectMapper(),env,http);

    void configured() { env.setProperty("native-push.enabled","true"); env.setProperty("native-push.bridge-url","https://bridge.example/native"); env.setProperty("native-push.bridge-secret","s".repeat(32)); }
    @Test void disabledMissingSecretAndNonHttpsFailClosedWithoutHttp() {
        assertThat(provider.send(1,2,"cid12345","zh").status()).isEqualTo("BLOCKED");
        env.setProperty("native-push.enabled","true");
        assertThat(provider.configurationState()).isEqualTo("MISSING_OR_INVALID_CONFIG");
        configured(); env.setProperty("native-push.bridge-url","http://bridge.example/native");
        assertThat(provider.send(1,2,"cid12345","zh").status()).isEqualTo("BLOCKED"); verifyNoInteractions(http);
    }
    @Test void controlledHttpResponseRecordsOnlyValidatedProviderReceiptAndSignsExactBody() throws Exception {
        configured();
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            HttpRequest request=inv.getArgument(0); var body=new StringBuilder();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer b) { body.append(StandardCharsets.UTF_8.decode(b)); }
                public void onError(Throwable t) { throw new AssertionError(t); }
                public void onComplete() { }
            });
            String timestamp=request.headers().firstValue("X-Push-Timestamp").orElseThrow();
            String nonce=request.headers().firstValue("X-Push-Nonce").orElseThrow();
            assertThat(request.headers().firstValue("X-Push-Signature").orElseThrow()).isEqualTo(UniPushBridgeClient.signature("s".repeat(32),timestamp,nonce,body.toString()));
            assertThat(body.toString()).contains("\"notificationId\":\"2\"").doesNotContain("password","route","amount");
            return response(200,"{\"requestId\":\"n1000000000000000000000000000001\",\"status\":\"ACCEPTED\",\"taskId\":\"task\"}");
        });
        assertThat(provider.send(1,2,"cid12345","zh")).isEqualTo(new UniPushBridgeClient.Result("ACCEPTED","BRIDGE_ACCEPTED","task"));
    }
    @Test void malformedHttpFailureAndMissingTaskCannotClaimAcceptance() throws Exception {
        configured(); var failed = response(503,"{}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(failed);
        assertThat(provider.send(1,2,"cid12345","en").status()).isEqualTo("RETRY");
        var noTask = response(200,"{\"requestId\":\"n1000000000000000000000000000001\",\"status\":\"ACCEPTED\"}");
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(noTask);
        assertThat(provider.send(1,2,"cid12345","en").status()).isEqualTo("UNKNOWN");
    }
    @Test void oversizedResponseCancelsWhileReceiving() {
        var body = new UniPushBridgeClient.LimitedResponse();
        var subscription = mock(Flow.Subscription.class); body.onSubscribe(subscription);
        body.onNext(java.util.List.of(ByteBuffer.wrap(new byte[8193])));
        verify(subscription).cancel(); assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    private HttpResponse<byte[]> response(int status,String body) {
        HttpResponse<byte[]> r=mock(HttpResponse.class); when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8)); return r;
    }
}
