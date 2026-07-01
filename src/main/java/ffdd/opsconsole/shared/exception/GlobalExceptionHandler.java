package ffdd.opsconsole.shared.exception;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private final AuditLogService auditLogService;

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException ex) {
        return ApiResult.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ApiResult<Void> handleValidation(Exception ex) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ApiResult<Void> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        auditAccessDenied(ex, request);
        return ApiResult.fail(403, "无权限访问");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResult<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "MEDIA_UPLOAD_TOO_LARGE");
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception ex) {
        return ApiResult.fail(500, ex.getMessage());
    }

    private void auditAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication == null ? null : authentication.getName();
            auditLogService.record(AuditLogWriteRequest.builder()
                    .action("A1_ACCESS_DENIED")
                    .resourceType("ADMIN_PERMISSION")
                    .resourceId(request == null ? null : request.getRequestURI())
                    .actorType("ADMIN")
                    .actorUsername(username)
                    .result("DENIED")
                    .riskLevel("MEDIUM")
                    .detail(Map.of(
                            "method", request == null ? "" : request.getMethod(),
                            "path", request == null ? "" : request.getRequestURI(),
                            "reason", ex.getMessage() == null ? "ACCESS_DENIED" : ex.getMessage()))
                    .build());
        } catch (RuntimeException ignored) {
            // Never let audit logging mask the permission denial response.
        }
    }
}
