package ffdd.gateway.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class GatewaySentinelRuleLoader {
    private static final Logger log = LoggerFactory.getLogger(GatewaySentinelRuleLoader.class);
    static final String RESOURCE_PREFIX = "gateway:";

    private final GatewaySentinelProperties properties;

    public GatewaySentinelRuleLoader(GatewaySentinelProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            FlowRuleManager.loadRules(List.of());
            DegradeRuleManager.loadRules(List.of());
            log.info("Gateway Sentinel rules disabled");
            return;
        }

        List<FlowRule> flowRules = new ArrayList<>();
        List<DegradeRule> degradeRules = new ArrayList<>();
        for (Map.Entry<String, GatewaySentinelProperties.RouteRule> routeEntry : properties.getRoutes().entrySet()) {
            String routeGroup = routeEntry.getKey();
            GatewaySentinelProperties.RouteRule routeRule = routeEntry.getValue();
            if (routeRule != null && Boolean.FALSE.equals(routeRule.getEnabled())) {
                continue;
            }
            String resource = resource(routeGroup);
            flowRules.add(flowRule(resource, routeRule));
            degradeRules.add(degradeRule(resource, routeRule));
        }
        FlowRuleManager.loadRules(flowRules);
        DegradeRuleManager.loadRules(degradeRules);
        log.info("Gateway Sentinel loaded flowRules={}, degradeRules={}", flowRules.size(), degradeRules.size());
    }

    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        if (event == null || event.getKeys() == null) {
            return;
        }
        boolean sentinelChanged = event.getKeys().stream().anyMatch(this::isSentinelConfigKey);
        if (sentinelChanged) {
            afterPropertiesSet();
        }
    }

    public static String resource(String routeGroup) {
        return RESOURCE_PREFIX + routeGroup;
    }

    private boolean isSentinelConfigKey(String key) {
        return key != null
                && (key.startsWith("nexion.gateway.sentinel.")
                || key.startsWith("spring.cloud.sentinel."));
    }

    private FlowRule flowRule(String resource, GatewaySentinelProperties.RouteRule routeRule) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(positiveDouble(
                routeRule == null ? null : routeRule.getFlowQps(),
                properties.getDefaultFlowQps(),
                1));
        return rule;
    }

    private DegradeRule degradeRule(String resource, GatewaySentinelProperties.RouteRule routeRule) {
        DegradeRule rule = new DegradeRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        rule.setCount(positiveDouble(
                routeRule == null ? null : routeRule.getSlowRtMs(),
                properties.getDefaultSlowRtMs(),
                100));
        rule.setMinRequestAmount(positiveInt(
                routeRule == null ? null : routeRule.getSlowRequestAmount(),
                properties.getDefaultSlowRequestAmount(),
                1));
        rule.setTimeWindow(positiveInt(
                routeRule == null ? null : routeRule.getDegradeTimeWindowSeconds(),
                properties.getDefaultDegradeTimeWindowSeconds(),
                1));
        rule.setStatIntervalMs(positiveInt(
                routeRule == null ? null : routeRule.getStatIntervalMs(),
                properties.getDefaultStatIntervalMs(),
                1000));
        return rule;
    }

    private double positiveDouble(Double routeValue, double defaultValue, double fallback) {
        if (routeValue != null && routeValue > 0) {
            return routeValue;
        }
        return defaultValue > 0 ? defaultValue : fallback;
    }

    private int positiveInt(Integer routeValue, int defaultValue, int fallback) {
        if (routeValue != null && routeValue > 0) {
            return routeValue;
        }
        return defaultValue > 0 ? defaultValue : fallback;
    }
}
