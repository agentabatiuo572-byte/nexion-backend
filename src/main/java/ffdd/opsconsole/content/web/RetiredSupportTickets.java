package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Keep historical ticket data, but retire every public ticket workflow. */
@Configuration
@RequiredArgsConstructor
public class RetiredSupportTickets implements WebMvcConfigurer {
    private final ObjectMapper json;

    public static boolean retired(String path) {
        // Seat capacity remains part of the live customer service workbench.
        if (path.equals("/api/admin/content/tickets/load-config")
                || path.equals("/api/admin/content/tickets/load-config/rebalance")) return false;
        return path.matches("/api/app/support(?:/acceptance)?/sla-targets")
                || path.matches("/api/admin/content/knowledge/sla(?:/.*)?")
                || path.matches("/api/(?:app/support(?:/acceptance)?|admin/content(?:/support/acceptance)?)/tickets(?:/.*)?")
                || path.matches("/api/(?:app/support(?:/acceptance)?|admin/content(?:/support/acceptance)?)/conversations/[^/]+/ticket");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (!retired(request.getServletPath())) return true;
                response.setStatus(410);
                response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), ApiResult.fail(410, "SUPPORT_TICKETS_RETIRED"));
                return false;
            }
        });
    }
}
