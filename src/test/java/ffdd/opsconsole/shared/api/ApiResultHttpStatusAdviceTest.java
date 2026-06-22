package ffdd.opsconsole.shared.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpResponse;

class ApiResultHttpStatusAdviceTest {
    private final ApiResultHttpStatusAdvice advice = new ApiResultHttpStatusAdvice();

    @Test
    void mapsFailureCodeToHttpStatus() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        advice.beforeBodyWrite(ApiResult.fail(
                OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                OpsErrorCode.COVERAGE_BELOW_REDLINE.name()), null, null, null, null, response);

        verify(response).setStatusCode(HttpStatusCode.valueOf(422));
    }

    @Test
    void mapsInvalidStateTransitionToConflictStatus() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        advice.beforeBodyWrite(ApiResult.fail(
                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                OpsErrorCode.INVALID_STATE_TRANSITION.name()), null, null, null, null, response);

        verify(response).setStatusCode(HttpStatusCode.valueOf(409));
    }

    @Test
    void mapsNonHttpErrorCodeToInternalServerError() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        advice.beforeBodyWrite(ApiResult.fail(9999, "UNKNOWN_ERROR_CODE"), null, null, null, null, response);

        verify(response).setStatusCode(HttpStatusCode.valueOf(500));
    }

    @Test
    void keepsSuccessfulResponseStatusUntouched() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        advice.beforeBodyWrite(ApiResult.ok("ok"), null, null, null, null, response);

        verify(response, never()).setStatusCode(org.mockito.ArgumentMatchers.any());
    }
}
