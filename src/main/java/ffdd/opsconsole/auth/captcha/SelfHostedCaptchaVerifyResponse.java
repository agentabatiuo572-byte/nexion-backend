package ffdd.opsconsole.auth.captcha;

public record SelfHostedCaptchaVerifyResponse(String ticket, int expiresInSec) { }
