package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.developer.application.AppDeveloperApiService;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates only the isolated developer OpenAPI namespace; it never performs a webhook call. */
@Component
@RequiredArgsConstructor
public class DeveloperApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final AppDeveloperApiService service;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/openapi/v1/developer")) {
            String raw = request.getHeader("X-API-Key");
            if (!StringUtils.hasText(raw)) {
                String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer sk_")) raw = authorization.substring(7);
            }
            if (StringUtils.hasText(raw)) {
                try {
                    Map<String, Object> identity = service.authenticate(raw.trim());
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            String.valueOf(identity.get("userId")), null, java.util.List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER_API")));
                    Map<String, String> details = new LinkedHashMap<>(); details.put("subjectType", "DEVELOPER_API_KEY"); details.put("keyId", String.valueOf(identity.get("keyId")));
                    authentication.setDetails(Map.copyOf(details)); SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (BizException ex) {
                    int status = ex.getCode() == 503 ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                            : HttpServletResponse.SC_UNAUTHORIZED;
                    SecurityContextHolder.clearContext(); response.setStatus(status); response.setContentType("application/json");
                    response.getWriter().write("{\"code\":" + status + ",\"message\":\"DEVELOPER_API_KEY_INVALID\",\"data\":null}"); return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
