package ffdd.opsconsole.auth.captcha;

/** Result returned only by a server-side or vendor-side verifier. */
public record CaptchaTicketVerification(boolean passed, String code) {
    public static CaptchaTicketVerification pass() { return new CaptchaTicketVerification(true, "OK"); }
    public static CaptchaTicketVerification reject(String code) { return new CaptchaTicketVerification(false, code); }
}
