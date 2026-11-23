package ffdd.opsconsole.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class UserOAuthExchangeRequest {
    private final String provider;
    private final String displayName;
    private final String challengeNo;
    private final String deviceName;

    public UserOAuthExchangeRequest(String provider, String displayName, String challengeNo) {
        this(provider, displayName, challengeNo, null, false);
    }

    /** Source-compatible constructor; legacy client mode/subject fields are intentionally discarded. */
    public UserOAuthExchangeRequest(String provider, String ignoredMode, String ignoredExternalSubject,
            String displayName) {
        this(provider, displayName, null, null, false);
    }

    /** Source-compatible constructor; the server profile and server-issued challenge remain authoritative. */
    public UserOAuthExchangeRequest(String provider, String ignoredMode, String ignoredExternalSubject,
            String displayName, String challengeNo) {
        this(provider, displayName, challengeNo, null, false);
    }

    private UserOAuthExchangeRequest(String provider, String displayName, String challengeNo, String deviceName,
            boolean ignored) {
        this.provider = provider;
        this.displayName = displayName;
        this.challengeNo = challengeNo;
        this.deviceName = deviceName;
    }

    @JsonCreator
    public static UserOAuthExchangeRequest fromJson(
            @JsonProperty("provider") String provider,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("challengeNo") String challengeNo,
            @JsonProperty("deviceName") String deviceName) {
        return new UserOAuthExchangeRequest(provider, displayName, challengeNo, deviceName, false);
    }

    public String provider() { return provider; }
    public String displayName() { return displayName; }
    public String challengeNo() { return challengeNo; }
    public String deviceName() { return deviceName; }

    @Override public String toString() {
        return "UserOAuthExchangeRequest[provider=" + provider + ", credentials=REDACTED]";
    }
}
