package ffdd.opsconsole.auth.captcha;

import java.util.List;

public record SelfHostedCaptchaVerifyRequest(
        String scene,
        String challengeId,
        Integer offsetX,
        List<SelfHostedCaptchaTrailPoint> trail,
        String inputMethod) { }
