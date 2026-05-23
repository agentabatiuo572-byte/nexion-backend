package ffdd.gateway.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class GatewaySentinelBlockResponderTest {
    private final GatewaySentinelBlockResponder responder = new GatewaySentinelBlockResponder();

    @Test
    void writesUniformJsonBlockResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/commerce/products").build());

        StepVerifier.create(responder.block(exchange, "gateway:commerce", new FlowException("gateway:commerce")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Sentinel-Block")).isEqualTo("true");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":429")
                .contains("\"sentinel block\"");
    }
}
