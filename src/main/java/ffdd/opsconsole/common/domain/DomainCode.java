package ffdd.opsconsole.common.domain;

public enum DomainCode {
    A("platform", "Platform foundation", "platform"),
    B("treasury", "Dual ledger cockpit", "treasury"),
    C("user", "User and account", "users"),
    D("finance", "Finance and wallet", "finance"),
    E("device", "Device and commerce", "devices"),
    F("team", "Team commission", "teams"),
    G("market", "NEX market and products", "markets"),
    H("growth", "Phase and growth", "growth"),
    I("content", "Content compliance and support", "content"),
    J("emergency", "Emergency compliance", "emergency"),
    K("risk", "Risk and anti abuse", "risk"),
    L("bi", "BI and reporting", "bi");

    private final String packageSegment;
    private final String displayName;
    private final String adminResource;

    DomainCode(String packageSegment, String displayName, String adminResource) {
        this.packageSegment = packageSegment;
        this.displayName = displayName;
        this.adminResource = adminResource;
    }

    public String packageSegment() {
        return packageSegment;
    }

    public String displayName() {
        return displayName;
    }

    public String adminResource() {
        return adminResource;
    }
}
