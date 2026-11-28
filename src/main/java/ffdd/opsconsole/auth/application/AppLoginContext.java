package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.exception.BizException;

/** Fixed at the credential-completion boundary; never reconstructed during refresh. */
public record AppLoginContext(AppSessionSurface group, String installationId, boolean executionSupported,
        String loginAttemptId, String recoveryProof, String authMethod, String authReference, String deviceName) {
    private static final int DEVICE_NAME_MAX_LENGTH = 128;

    public AppLoginContext {
        if (group == AppSessionSurface.H5 && installationId == null) installationId = "";
        deviceName = normalizeDeviceName(deviceName);
    }

    /** Source-compatible constructor for internal callers that do not display a client device name. */
    public AppLoginContext(AppSessionSurface group, String installationId, boolean executionSupported,
            String loginAttemptId, String recoveryProof, String authMethod, String authReference) {
        this(group, installationId, executionSupported, loginAttemptId, recoveryProof, authMethod, authReference, null);
    }

    public void validate() {
        if (group == null || group == AppSessionSurface.UNKNOWN)
            throw new BizException(401, "SESSION_CONTEXT_REAUTH_REQUIRED");
        if (installationId == null || (!installationId.isEmpty() && !installationId.matches("[A-Za-z0-9._:-]{1,128}"))
                || group == AppSessionSurface.APP && installationId.isEmpty())
            throw new BizException(422, "LOGIN_INSTALLATION_INVALID");
        if (loginAttemptId == null || !loginAttemptId.matches("[A-Za-z0-9_-]{16,80}")
                || recoveryProof == null || !recoveryProof.matches("[A-Za-z0-9_-]{43,128}"))
            throw new BizException(422, "LOGIN_ATTEMPT_INVALID");
        if (authMethod == null || !authMethod.matches("PASSWORD|PASSWORD_RESET|OTP|TWO_FACTOR|OAUTH|REGISTRATION")
                || authReference == null || authReference.isBlank() || authReference.length() > 256)
            throw new BizException(422, "LOGIN_ATTEMPT_CONTEXT_INVALID");
        if (group == AppSessionSurface.H5 && executionSupported)
            throw new BizException(422, "LOGIN_EXECUTION_CONTEXT_INVALID");
    }

    /** Legacy internal overloads cannot silently manufacture a usable session. */
    public static AppLoginContext unavailable(AppSessionSurface ignored) {
        return new AppLoginContext(AppSessionSurface.UNKNOWN, null, false, null, null, null, null);
    }

    /** Display-only client name: controls removed, trimmed, and capped at the session column width. */
    private static String normalizeDeviceName(String value) {
        if (value == null) return null;
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().filter(codePoint -> !Character.isISOControl(codePoint)
                        && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE))
                .forEach(cleaned::appendCodePoint);
        String text = cleaned.toString().strip();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > DEVICE_NAME_MAX_LENGTH)
            text = text.substring(0, text.offsetByCodePoints(0, DEVICE_NAME_MAX_LENGTH));
        return text;
    }

    @Override public String toString() {
        return "AppLoginContext[group=" + group + ", credentials=REDACTED]";
    }
}
