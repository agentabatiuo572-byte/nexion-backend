package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.OAuthSandboxChallengeService;
import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthSandboxChallengeResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Automated-test OAuth fixture. Deployable dev/prod runtimes do not register this route. */
@RestController
@Profile("test")
@RequestMapping("/auth/users")
@RequiredArgsConstructor
public class OAuthSandboxChallengeController {
    private final OAuthSandboxChallengeService challengeService;

    @PostMapping("/oauth/sandbox/challenge")
    public ApiResult<UserOAuthSandboxChallengeResponse> issue(
            @RequestBody(required = false) UserOAuthSandboxChallengeRequest request,
            HttpServletRequest servletRequest) {
        return challengeService.issue(request, servletRequest.getRemoteAddr(),
                servletRequest.getHeader("Origin"));
    }
}
