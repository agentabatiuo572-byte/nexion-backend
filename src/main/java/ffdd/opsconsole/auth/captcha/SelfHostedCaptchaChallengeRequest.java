package ffdd.opsconsole.auth.captcha;

/** Public request deliberately carries only the purpose, never a provider credential. */
public record SelfHostedCaptchaChallengeRequest(String scene) { }
