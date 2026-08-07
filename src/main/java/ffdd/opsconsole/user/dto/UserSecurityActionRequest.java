package ffdd.opsconsole.user.dto;

public record UserSecurityActionRequest(
        String reason,
        String operator,
        Boolean operatorConfirmed,
        String lockKind) {

    public UserSecurityActionRequest(String reason, String operator) {
        this(reason, operator, false, null);
    }
}
