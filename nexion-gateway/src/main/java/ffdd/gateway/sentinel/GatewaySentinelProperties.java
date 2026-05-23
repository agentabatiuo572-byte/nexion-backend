package ffdd.gateway.sentinel;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexion.gateway.sentinel")
public class GatewaySentinelProperties {
    private boolean enabled = true;
    private double defaultFlowQps = 1000;
    private double defaultSlowRtMs = 1500;
    private int defaultSlowRequestAmount = 20;
    private int defaultDegradeTimeWindowSeconds = 5;
    private int defaultStatIntervalMs = 10000;
    private Map<String, RouteRule> routes = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getDefaultFlowQps() {
        return defaultFlowQps;
    }

    public void setDefaultFlowQps(double defaultFlowQps) {
        this.defaultFlowQps = defaultFlowQps;
    }

    public double getDefaultSlowRtMs() {
        return defaultSlowRtMs;
    }

    public void setDefaultSlowRtMs(double defaultSlowRtMs) {
        this.defaultSlowRtMs = defaultSlowRtMs;
    }

    public int getDefaultSlowRequestAmount() {
        return defaultSlowRequestAmount;
    }

    public void setDefaultSlowRequestAmount(int defaultSlowRequestAmount) {
        this.defaultSlowRequestAmount = defaultSlowRequestAmount;
    }

    public int getDefaultDegradeTimeWindowSeconds() {
        return defaultDegradeTimeWindowSeconds;
    }

    public void setDefaultDegradeTimeWindowSeconds(int defaultDegradeTimeWindowSeconds) {
        this.defaultDegradeTimeWindowSeconds = defaultDegradeTimeWindowSeconds;
    }

    public int getDefaultStatIntervalMs() {
        return defaultStatIntervalMs;
    }

    public void setDefaultStatIntervalMs(int defaultStatIntervalMs) {
        this.defaultStatIntervalMs = defaultStatIntervalMs;
    }

    public Map<String, RouteRule> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteRule> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : routes;
    }

    public static class RouteRule {
        private Boolean enabled;
        private Double flowQps;
        private Double slowRtMs;
        private Integer slowRequestAmount;
        private Integer degradeTimeWindowSeconds;
        private Integer statIntervalMs;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Double getFlowQps() {
            return flowQps;
        }

        public void setFlowQps(Double flowQps) {
            this.flowQps = flowQps;
        }

        public Double getSlowRtMs() {
            return slowRtMs;
        }

        public void setSlowRtMs(Double slowRtMs) {
            this.slowRtMs = slowRtMs;
        }

        public Integer getSlowRequestAmount() {
            return slowRequestAmount;
        }

        public void setSlowRequestAmount(Integer slowRequestAmount) {
            this.slowRequestAmount = slowRequestAmount;
        }

        public Integer getDegradeTimeWindowSeconds() {
            return degradeTimeWindowSeconds;
        }

        public void setDegradeTimeWindowSeconds(Integer degradeTimeWindowSeconds) {
            this.degradeTimeWindowSeconds = degradeTimeWindowSeconds;
        }

        public Integer getStatIntervalMs() {
            return statIntervalMs;
        }

        public void setStatIntervalMs(Integer statIntervalMs) {
            this.statIntervalMs = statIntervalMs;
        }
    }
}
