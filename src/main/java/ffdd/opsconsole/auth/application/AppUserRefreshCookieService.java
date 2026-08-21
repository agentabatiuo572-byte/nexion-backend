package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeResponse;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Browser refresh credentials live only in an HttpOnly cookie. Native clients
 * keep the existing response-body contract until a platform keystore adapter is
 * available; the explicit request header prevents the two credential rails from
 * being selected accidentally.
 */
@Service
@RequiredArgsConstructor
public class AppUserRefreshCookieService {
    public static final String MODE_HEADER = "X-Nexion-Refresh-Mode";
    public static final String COOKIE_MODE = "cookie";
    public static final String COOKIE_NAME = "NEXION_APP_REFRESH";
    private static final String COOKIE_PATH = "/auth/users";
    private static final Duration COOKIE_TTL = Duration.ofDays(30);

    private final Environment environment;

    public ApiResult<UserLoginResponse> issue(
            ApiResult<UserLoginResponse> result,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (!cookieMode(request) || result == null || result.getCode() != 0 || result.getData() == null) {
            return result;
        }
        UserLoginResponse data = result.getData();
        if (!StringUtils.hasText(data.refreshToken())) return result;
        write(response, data.refreshToken(), COOKIE_TTL);
        UserLoginResponse redacted = new UserLoginResponse(
                data.accessToken(), data.tokenType(), data.user(), data.challengeNo(),
                data.deliveryHint(), null, data.registrationReceipt());
        return new ApiResult<>(result.getCode(), result.getMessage(), redacted);
    }

    public ApiResult<UserOAuthExchangeResponse> issueOAuth(
            ApiResult<UserOAuthExchangeResponse> result,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (!cookieMode(request) || result == null || result.getCode() != 0 || result.getData() == null) {
            return result;
        }
        UserOAuthExchangeResponse data = result.getData();
        if (!StringUtils.hasText(data.refreshToken())) return result;
        write(response, data.refreshToken(), COOKIE_TTL);
        UserOAuthExchangeResponse redacted = new UserOAuthExchangeResponse(
                data.accessToken(), data.tokenType(), data.user(), null, data.source(), data.sandbox());
        return new ApiResult<>(result.getCode(), result.getMessage(), redacted);
    }

    public UserRefreshRequest resolve(UserRefreshRequest body, HttpServletRequest request) {
        String bodyToken = body == null ? null : normalize(body.refreshToken());
        String cookieToken = cookieMode(request) ? cookie(request) : null;
        if (bodyToken != null && cookieToken != null && !bodyToken.equals(cookieToken)) {
            throw new BizException(401, "USER_REFRESH_CREDENTIAL_CONFLICT");
        }
        return new UserRefreshRequest(cookieMode(request) ? cookieToken : bodyToken);
    }

    public boolean cookieMode(HttpServletRequest request) {
        return request != null && COOKIE_MODE.equalsIgnoreCase(request.getHeader(MODE_HEADER));
    }

    public void clear(HttpServletResponse response) {
        noStore(response);
        write(response, "", Duration.ZERO);
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .map(AppUserRefreshCookieService::normalize)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private void write(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean secureCookie() {
        // Only the one strict local-development profile is allowed to use an
        // HTTP cookie. Missing, mixed or unknown profiles fail closed to the
        // HTTPS-only form even if application startup validation is bypassed.
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles.length != 1 || !"dev".equalsIgnoreCase(profiles[0]);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
