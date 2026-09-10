package ffdd.opsconsole.auth.captcha;

import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous boundary for the first-party slider. Client IP comes from the container, not request headers. */
@RestController
@RequestMapping("/api/auth/captcha")
@RequiredArgsConstructor
public class SelfHostedCaptchaController {
    private final SelfHostedCaptchaService captcha;

    @PostMapping("/challenge")
    public ApiResult<SelfHostedCaptchaChallengeResponse> challenge(
            @RequestBody(required = false) SelfHostedCaptchaChallengeRequest request,
            HttpServletRequest servletRequest) {
        return captcha.createChallenge(request, servletRequest.getRemoteAddr());
    }

    @PostMapping("/verify")
    public ApiResult<SelfHostedCaptchaVerifyResponse> verify(
            @RequestBody(required = false) SelfHostedCaptchaVerifyRequest request,
            HttpServletRequest servletRequest) {
        return captcha.verifyChallenge(request, servletRequest.getRemoteAddr());
    }
}
