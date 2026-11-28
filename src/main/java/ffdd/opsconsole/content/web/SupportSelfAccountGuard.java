package ffdd.opsconsole.content.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class SupportSelfAccountGuard {
    private static final String EXPECTED_ADMIN_ID = "X-Expected-Admin-Id";
    private static final String ACCOUNT_MISMATCH = "SELF_ACCOUNT_MISMATCH";

    private SupportSelfAccountGuard() {
    }

    static void requireMatch(HttpServletRequest request) {
        if (!matches(request)) {
            throw new BizException(HttpStatus.CONFLICT.value(), ACCOUNT_MISMATCH);
        }
    }

    static boolean matches(HttpServletRequest request) {
        var expectedIds = request.getHeaders(EXPECTED_ADMIN_ID);
        if (!expectedIds.hasMoreElements()) {
            return true;
        }

        String expectedId = expectedIds.nextElement();
        return !expectedIds.hasMoreElements() && expectedId.equals(AdminActorResolver.subjectId());
    }

    static ResponseEntity<ApiResult<Void>> mismatchResponse() {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResult.fail(HttpStatus.CONFLICT.value(), ACCOUNT_MISMATCH));
    }
}
