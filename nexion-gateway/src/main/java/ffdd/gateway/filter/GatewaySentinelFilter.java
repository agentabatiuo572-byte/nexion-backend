package ffdd.gateway.filter;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import ffdd.gateway.sentinel.GatewaySentinelBlockResponder;
import ffdd.gateway.sentinel.GatewaySentinelProperties;
import ffdd.gateway.sentinel.GatewaySentinelRuleLoader;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewaySentinelFilter implements GlobalFilter, Ordered {
    private static final String API_PREFIX = "/api/";

    private final GatewaySentinelProperties properties;
    private final GatewaySentinelBlockResponder blockResponder;

    public GatewaySentinelFilter(
            GatewaySentinelProperties properties,
            GatewaySentinelBlockResponder blockResponder) {
        this.properties = properties;
        this.blockResponder = blockResponder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!properties.isEnabled() || !path.startsWith(API_PREFIX)) {
            return chain.filter(exchange);
        }

        String resource = GatewaySentinelRuleLoader.resource(routeGroup(path));
        Entry entry;
        try {
            entry = SphU.entry(resource);
        } catch (BlockException ex) {
            return blockResponder.block(exchange, resource, ex);
        }

        AtomicReference<Entry> entryRef = new AtomicReference<>(entry);
        return chain.filter(exchange)
                .doOnError(ex -> Tracer.trace(ex))
                .doFinally(signalType -> {
                    Entry current = entryRef.getAndSet(null);
                    if (current != null) {
                        current.exit();
                    }
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private String routeGroup(String path) {
        String remaining = path.substring(API_PREFIX.length());
        int slash = remaining.indexOf('/');
        return slash < 0 ? remaining : remaining.substring(0, slash);
    }
}
