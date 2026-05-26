package ffdd.common.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnClass({HttpServletRequest.class, OncePerRequestFilter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditTraceFilter extends OncePerRequestFilter {
    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final String TRACE_ID_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String previousTraceId = MDC.get(AuditTraceContext.MDC_TRACE_ID);
        String traceId = normalizeTraceId(request.getHeader(AuditTraceContext.TRACE_ID_HEADER));
        request.setAttribute(AuditTraceContext.REQUEST_ATTRIBUTE_TRACE_ID, traceId);
        response.setHeader(AuditTraceContext.TRACE_ID_HEADER, traceId);
        MDC.put(AuditTraceContext.MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (StringUtils.hasText(previousTraceId)) {
                MDC.put(AuditTraceContext.MDC_TRACE_ID, previousTraceId);
            } else {
                MDC.remove(AuditTraceContext.MDC_TRACE_ID);
            }
        }
    }

    private String normalizeTraceId(String headerValue) {
        if (StringUtils.hasText(headerValue)) {
            String trimmed = headerValue.trim();
            if (trimmed.length() <= MAX_TRACE_ID_LENGTH && trimmed.matches(TRACE_ID_PATTERN)) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
