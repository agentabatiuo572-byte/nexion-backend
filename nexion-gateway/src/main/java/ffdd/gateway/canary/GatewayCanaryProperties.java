package ffdd.gateway.canary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexion.gateway.canary")
public class GatewayCanaryProperties {
    private boolean enabled;
    private String forceHeaderName = "X-Nexion-Canary";
    private String forceHeaderValue = "true";
    private String versionHeaderName = "X-App-Version";
    private Map<String, RouteRule> routes = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getForceHeaderName() {
        return forceHeaderName;
    }

    public void setForceHeaderName(String forceHeaderName) {
        this.forceHeaderName = forceHeaderName;
    }

    public String getForceHeaderValue() {
        return forceHeaderValue;
    }

    public void setForceHeaderValue(String forceHeaderValue) {
        this.forceHeaderValue = forceHeaderValue;
    }

    public String getVersionHeaderName() {
        return versionHeaderName;
    }

    public void setVersionHeaderName(String versionHeaderName) {
        this.versionHeaderName = versionHeaderName;
    }

    public Map<String, RouteRule> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteRule> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : routes;
    }

    public static class RouteRule {
        private boolean enabled;
        private String canaryUri;
        private int percent;
        private String forceHeaderName;
        private String forceHeaderValue;
        private List<String> versions = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCanaryUri() {
            return canaryUri;
        }

        public void setCanaryUri(String canaryUri) {
            this.canaryUri = canaryUri;
        }

        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }

        public String getForceHeaderName() {
            return forceHeaderName;
        }

        public void setForceHeaderName(String forceHeaderName) {
            this.forceHeaderName = forceHeaderName;
        }

        public String getForceHeaderValue() {
            return forceHeaderValue;
        }

        public void setForceHeaderValue(String forceHeaderValue) {
            this.forceHeaderValue = forceHeaderValue;
        }

        public List<String> getVersions() {
            return versions;
        }

        public void setVersions(List<String> versions) {
            this.versions = versions == null ? new ArrayList<>() : versions;
        }
    }
}
