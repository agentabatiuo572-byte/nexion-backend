package ffdd.opsconsole.shared.audit;

import ffdd.opsconsole.shared.security.AuthHeaders;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class AuditTraceContext {
    public static final String TRACE_ID_HEADER = AuthHeaders.TRACE_ID;
    public static final String MDC_TRACE_ID = "traceId";
    public static final String REQUEST_ATTRIBUTE_TRACE_ID = "nexionTraceId";

    private AuditTraceContext() {
    }

    public static String currentTraceId() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return StringUtils.hasText(traceId) ? traceId : null;
    }
}
