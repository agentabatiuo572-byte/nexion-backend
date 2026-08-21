package ffdd.opsconsole.auth.dto;

public record UserOAuthExchangeRequest(
        String provider,
        String displayName,
        String challengeNo) {

    /** Source-compatible constructor; legacy client mode/subject fields are intentionally discarded. */
    public UserOAuthExchangeRequest(String provider, String ignoredMode, String ignoredExternalSubject,
            String displayName) {
        this(provider, displayName, null);
    }

    /** Source-compatible constructor; the server profile and server-issued challenge remain authoritative. */
    public UserOAuthExchangeRequest(String provider, String ignoredMode, String ignoredExternalSubject,
            String displayName, String challengeNo) {
        this(provider, displayName, challengeNo);
    }
}
