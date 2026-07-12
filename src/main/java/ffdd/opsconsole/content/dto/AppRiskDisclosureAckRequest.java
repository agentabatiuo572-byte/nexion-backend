package ffdd.opsconsole.content.dto;

public record AppRiskDisclosureAckRequest(
        String jurisdiction, String version, String acknowledgmentToken, Boolean confirmed) {
    public AppRiskDisclosureAckRequest(String jurisdiction, String version) {
        this(jurisdiction, version, null, false);
    }

    public AppRiskDisclosureAckRequest(String jurisdiction, String version, String acknowledgmentToken) {
        this(jurisdiction, version, acknowledgmentToken, true);
    }
}
