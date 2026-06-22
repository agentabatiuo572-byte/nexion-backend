package ffdd.opsconsole.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
    void illegalArgumentReturns422() {
        ApiResult<Void> result = handler.handleIllegalArgument(new IllegalArgumentException("invalid transition reason"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("invalid transition reason");
    }

    @Test
    void accessDeniedReturns403() {
        ApiResult<Void> result = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("无权限访问");
    }

    @Test
    void unexpectedExceptionReturns500() {
        ApiResult<Void> result = handler.handleException(new RuntimeException("database unavailable"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INTERNAL_ERROR.httpStatus());
        assertThat(result.getMessage()).isEqualTo("database unavailable");
    }
}
