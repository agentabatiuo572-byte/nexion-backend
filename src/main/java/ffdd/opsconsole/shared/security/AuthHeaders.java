package ffdd.opsconsole.shared.security;

public final class AuthHeaders {
    public static final String SUBJECT_ID = "X-Nexion-Subject-Id";
    public static final String SUBJECT_TYPE = "X-Nexion-Subject-Type";
    public static final String USERNAME = "X-Nexion-Username";
    public static final String AUTHORITIES = "X-Nexion-Authorities";
    public static final String GATEWAY_SECRET = "X-Nexion-Gateway-Secret";
    public static final String TRACE_ID = "X-Nexion-Trace-Id";

    private AuthHeaders() {
    }
}
