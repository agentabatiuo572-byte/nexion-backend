package ffdd.gateway.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
