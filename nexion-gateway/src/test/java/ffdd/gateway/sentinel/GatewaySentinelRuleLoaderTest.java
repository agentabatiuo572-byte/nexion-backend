package ffdd.gateway.sentinel;

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
}
