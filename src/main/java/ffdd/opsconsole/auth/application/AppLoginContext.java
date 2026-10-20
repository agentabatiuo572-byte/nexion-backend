package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.exception.BizException;

/** Fixed at the credential-completion boundary; never reconstructed during refresh. */
public record AppLoginContext(AppSessionSurface group, String installationId, boolean executionSupported,
        String loginAttemptId, String recoveryProof, String authMethod, String authReference) {
    public AppLoginContext {
        if (group == AppSessionSurface.H5 && installationId == null) installationId = "";
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

    @Override public String toString() {
        return "AppLoginContext[group=" + group + ", credentials=REDACTED]";
    }
}
