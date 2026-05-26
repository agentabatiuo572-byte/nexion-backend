package ffdd.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuditTraceFilterTest {
    private final AuditTraceFilter filter = new AuditTraceFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void preservesValidIncomingTraceIdAndReturnsIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallet/withdrawals");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(AuditTraceContext.TRACE_ID_HEADER, "trace-abc-123");

        filter.doFilter(request, response, assertTrace("trace-abc-123"));

        assertThat(response.getHeader(AuditTraceContext.TRACE_ID_HEADER)).isEqualTo("trace-abc-123");
        assertThat(MDC.get(AuditTraceContext.MDC_TRACE_ID)).isNull();
    }

    @Test
    void replacesInvalidTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallet/withdrawals");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(AuditTraceContext.TRACE_ID_HEADER, "bad trace id with spaces");

        filter.doFilter(request, response, assertTraceNot("bad trace id with spaces"));

        assertThat(response.getHeader(AuditTraceContext.TRACE_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("bad trace id with spaces");
    }

    private FilterChain assertTrace(String expectedTraceId) {
        return (request, response) -> assertThat(MDC.get(AuditTraceContext.MDC_TRACE_ID)).isEqualTo(expectedTraceId);
    }

    private FilterChain assertTraceNot(String forbiddenTraceId) {
        return (request, response) -> assertThat(MDC.get(AuditTraceContext.MDC_TRACE_ID)).isNotEqualTo(forbiddenTraceId);
    }
}
