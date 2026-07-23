package ffdd.opsconsole.user.dto;

public record UserImpersonationRequest(
        Integer ttlMinutes,
        String reasonCode,
        String reason,
        String operator) {

    public UserImpersonationRequest(Integer ttlMinutes, String reason, String operator) {
        this(ttlMinutes, null, reason, operator);
    }
}
