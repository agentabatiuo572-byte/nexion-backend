package ffdd.opsconsole.janus.domain;

public enum JanusRole {
    VIEWER,
    OPERATOR,
    SENIOR_OPERATOR,
    ADMIN;

    public boolean atLeast(JanusRole required) {
        return ordinal() >= required.ordinal();
    }
}
