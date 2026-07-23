package ffdd.opsconsole.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(auditLogService);

    @Test
    void bizExceptionKeepsDomainErrorCodeAndMessage() {
        ApiResult<Void> result = handler.handleBiz(new BizException(
                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                OpsErrorCode.INVALID_STATE_TRANSITION.name()));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void validationExceptionReturns422() {
        ApiResult<Void> result = handler.handleValidation(
                new ConstraintViolationException("operator is required", Set.of()));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).contains("operator is required");
    }

    @Test
    void missingIdempotencyHeaderReturnsStable422() {
        ApiResult<Void> result = handler.handleMissingRequestHeader(
                new MissingRequestHeaderException("Idempotency-Key", mock(MethodParameter.class)));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void otherMissingHeaderIsNotMisclassifiedAsIdempotencyFailure() {
        ApiResult<Void> result = handler.handleMissingRequestHeader(
                new MissingRequestHeaderException("X-Tenant-Id", mock(MethodParameter.class)));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("REQUEST_HEADER_REQUIRED");
    }

    @Test
    void illegalArgumentReturns422() {
        ApiResult<Void> result = handler.handleIllegalArgument(new IllegalArgumentException("invalid transition reason"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("invalid transition reason");
    }

    @Test
    void accessDeniedReturns403() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users/freeze");

        ApiResult<Void> result = handler.handleAccessDenied(new AccessDeniedException("denied"), request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("无权限访问");
        verify(auditLogService).record(argThat(audit ->
                "A1_ACCESS_DENIED".equals(audit.getAction())
                        && "ADMIN_PERMISSION".equals(audit.getResourceType())
                        && "/api/admin/users/freeze".equals(audit.getResourceId())
                        && "DENIED".equals(audit.getResult())));
    }

    @Test
    void maxUploadSizeExceededReturnsStableMediaError() {
        ApiResult<Void> result = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(200L * 1024 * 1024));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("MEDIA_UPLOAD_TOO_LARGE");
    }

    @Test
    void unexpectedExceptionReturnsStableSanitized500() {
        ApiResult<Void> result = handler.handleException(new RuntimeException(
                "MysqlDataTruncation: nx_admin_idempotency_record AdminIdempotencyRecordMapper INSERT ..."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INTERNAL_ERROR.httpStatus());
        assertThat(result.getMessage()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(result.getMessage()).doesNotContain("Mysql", "Mapper", "INSERT");
    }

    @Test
    void malformedRequestBodyReturnsStable400WithoutParserDetails() {
        ApiResult<Void> result = handler.handleUnreadableMessage(
                new HttpMessageNotReadableException("JSON parse error: Long from string"));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("REQUEST_BODY_INVALID");
    }

    @Test
    void unsupportedHttpMethodReturnsStable405() {
        ApiResult<Void> result = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertThat(result.getCode()).isEqualTo(405);
        assertThat(result.getMessage()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void removedOrUnknownRouteReturnsStable404() {
        ApiResult<Void> result = handler.handleNoResource(new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/admin/users/profiles/52"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("RESOURCE_NOT_FOUND");
    }
}
