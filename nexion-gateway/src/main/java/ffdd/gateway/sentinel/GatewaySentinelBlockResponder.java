package ffdd.gateway.sentinel;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewaySentinelBlockResponder {
    public Mono<Void> block(ServerWebExchange exchange, String resource, BlockException exception) {
        boolean degraded = exception instanceof DegradeException;
        HttpStatus status = degraded ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
        String blockType = degraded ? "degrade" : "flow";
        String message = degraded ? "sentinel degraded" : "sentinel flow control";
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\",\"data\":{"
                        + "\"resource\":\"" + resource + "\","
                        + "\"type\":\"" + blockType + "\","
                        + "\"rule\":\"" + exception.getClass().getSimpleName() + "\""
                        + "}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        exchange.getResponse().getHeaders().set("X-Sentinel-Block", "true");
        exchange.getResponse().getHeaders().set("X-Sentinel-Block-Type", blockType);
        exchange.getResponse().getHeaders().set("X-Sentinel-Resource", resource);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory()
                .wrap(body)));
    }
}
