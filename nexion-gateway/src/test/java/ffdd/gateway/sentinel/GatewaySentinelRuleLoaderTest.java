package ffdd.gateway.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;

class GatewaySentinelRuleLoaderTest {
    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void loadsRouteFlowRulesThatBlockWhenQpsExceeded() throws BlockException {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();
        properties.setEnabled(true);
        properties.setDefaultFlowQps(1);
        properties.getRoutes().put("commerce", new GatewaySentinelProperties.RouteRule());

        new GatewaySentinelRuleLoader(properties).afterPropertiesSet();

        Entry first = SphU.entry("gateway:commerce");
        first.exit();

        assertThatThrownBy(() -> SphU.entry("gateway:commerce"))
                .isInstanceOf(BlockException.class);
    }

    @Test
    void loadsRouteDegradeRulesWithRouteOverrides() {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();
        properties.setEnabled(true);
        properties.setDefaultSlowRtMs(1500);
        properties.setDefaultSlowRequestAmount(20);
        properties.setDefaultDegradeTimeWindowSeconds(5);
        properties.setDefaultStatIntervalMs(10000);
        GatewaySentinelProperties.RouteRule routeRule = new GatewaySentinelProperties.RouteRule();
        routeRule.setSlowRtMs(250.0);
        routeRule.setSlowRequestAmount(3);
        routeRule.setDegradeTimeWindowSeconds(9);
        routeRule.setStatIntervalMs(2000);
        properties.getRoutes().put("wallet", routeRule);

        new GatewaySentinelRuleLoader(properties).afterPropertiesSet();

        assertThat(DegradeRuleManager.getRules()).hasSize(1);
        assertThat(DegradeRuleManager.getRules().get(0).getResource()).isEqualTo("gateway:wallet");
        assertThat(DegradeRuleManager.getRules().get(0).getCount()).isEqualTo(250.0);
        assertThat(DegradeRuleManager.getRules().get(0).getMinRequestAmount()).isEqualTo(3);
        assertThat(DegradeRuleManager.getRules().get(0).getTimeWindow()).isEqualTo(9);
        assertThat(DegradeRuleManager.getRules().get(0).getStatIntervalMs()).isEqualTo(2000);
    }

    @Test
    void reloadsRulesWhenSentinelEnvironmentChanges() {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();
        properties.setEnabled(true);
        GatewaySentinelProperties.RouteRule routeRule = new GatewaySentinelProperties.RouteRule();
        routeRule.setFlowQps(2.0);
        properties.getRoutes().put("commerce", routeRule);
        GatewaySentinelRuleLoader loader = new GatewaySentinelRuleLoader(properties);

        loader.afterPropertiesSet();
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(2.0);

        routeRule.setFlowQps(7.0);
        loader.onEnvironmentChange(new EnvironmentChangeEvent(Set.of(
                "nexion.gateway.sentinel.routes.commerce.flow-qps")));

        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(FlowRuleManager.getRules().get(0).getResource()).isEqualTo("gateway:commerce");
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(7.0);
    }

    @Test
    void ignoresUnrelatedEnvironmentChanges() {
        GatewaySentinelProperties properties = new GatewaySentinelProperties();
        properties.setEnabled(true);
        GatewaySentinelProperties.RouteRule routeRule = new GatewaySentinelProperties.RouteRule();
        routeRule.setFlowQps(2.0);
        properties.getRoutes().put("wallet", routeRule);
        GatewaySentinelRuleLoader loader = new GatewaySentinelRuleLoader(properties);

        loader.afterPropertiesSet();
        routeRule.setFlowQps(9.0);
        loader.onEnvironmentChange(new EnvironmentChangeEvent(Set.of("nexion.gateway.rate-limit.enabled")));

        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(FlowRuleManager.getRules().get(0).getResource()).isEqualTo("gateway:wallet");
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(2.0);
    }
}
