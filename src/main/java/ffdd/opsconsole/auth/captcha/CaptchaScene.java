package ffdd.opsconsole.auth.captcha;

/** Purpose binding for a CAPTCHA assertion. A ticket is never transferable across scenes. */
public enum CaptchaScene {
    REGISTER,
    LOGIN,
    RESET
}
