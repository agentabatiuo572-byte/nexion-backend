package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AppUserRefreshCookieServiceTest {
    private static final String RAW_REFRESH = "refresh-secret-that-must-not-enter-h5-json";

    @Test
    void h5ModeWritesAnHttpOnlyStrictCookieAndRedactsTheJsonCredential() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(environment);
        MockHttpServletRequest request = cookieModeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResult<UserLoginResponse> result = service.issue(loginResult(), request, response);

        assertThat(result.getData().refreshToken()).isNull();
        assertThat(response.getHeader("Set-Cookie"))
                .contains("NEXION_APP_REFRESH=")
                .contains("Path=/auth/users")
                .contains("Max-Age=")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Secure");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void productionCookieIsSecure() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(environment);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issue(loginResult(), cookieModeRequest(), response);

        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }

    @Test
    void missingOrUnknownRuntimeProfileFailsClosedToSecureCookie() {
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.issue(loginResult(), cookieModeRequest(), response);

        assertThat(response.getHeader("Set-Cookie")).contains("Secure");
    }

    @Test
    void cookieModeRefreshUsesOnlyTheHttpOnlyCookie() {
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(new MockEnvironment());
        MockHttpServletRequest request = cookieModeRequest();
        request.setCookies(new Cookie("NEXION_APP_REFRESH", RAW_REFRESH));

        UserRefreshRequest resolved = service.resolve(null, request);

        assertThat(resolved.refreshToken()).isEqualTo(RAW_REFRESH);
    }

    @Test
    void conflictingBodyAndCookieCredentialsFailClosed() {
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(new MockEnvironment());
        MockHttpServletRequest request = cookieModeRequest();
        request.setCookies(new Cookie("NEXION_APP_REFRESH", RAW_REFRESH));

        assertThatThrownBy(() -> service.resolve(new UserRefreshRequest("different"), request))
                .isInstanceOf(BizException.class)
                .hasMessage("USER_REFRESH_CREDENTIAL_CONFLICT");
    }

    @Test
    void logoutExpiresTheCookieWithoutEchoingTheCredential() {
        AppUserRefreshCookieService service = new AppUserRefreshCookieService(new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("NEXION_APP_REFRESH=")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .doesNotContain(RAW_REFRESH);
    }

    private MockHttpServletRequest cookieModeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Nexion-Refresh-Mode", "cookie");
        return request;
    }

    private ApiResult<UserLoginResponse> loginResult() {
        return ApiResult.ok(new UserLoginResponse("access", "Bearer",
                new UserLoginResponse.UserSession(3775L, "+86", "18708173775", "Nexion 3775"),
                RAW_REFRESH));
    }
}
