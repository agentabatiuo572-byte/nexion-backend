package ffdd.opsconsole.emergency.application;

/** Server-side geo policy result for one trusted edge-country decision. */
public record GeoBlockDecision(boolean blocked, String code, String policyStatus, String endpointKey) {
    public static GeoBlockDecision allowed() {
        return new GeoBlockDecision(false, "", "allowed", null);
    }

    public static GeoBlockDecision blocked(String code, String policyStatus, String endpointKey) {
        return new GeoBlockDecision(true, code, policyStatus, endpointKey);
    }
}
