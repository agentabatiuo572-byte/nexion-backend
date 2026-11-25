package ffdd.opsconsole.content.web;

import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import jakarta.servlet.http.HttpServletRequest;

final class SupportSelfAccountGuard {
    private static final String EXPECTED_ADMIN_ID = "X-Expected-Admin-Id";

    private SupportSelfAccountGuard() {
    }

    static void requireMatch(HttpServletRequest request) {
        var expectedIds = request.getHeaders(EXPECTED_ADMIN_ID);
        if (!expectedIds.hasMoreElements()) {
            return;
        }

        String expectedId = expectedIds.nextElement();
        if (expectedIds.hasMoreElements() || !expectedId.equals(AdminActorResolver.subjectId())) {
            throw new BizException(409, "SELF_ACCOUNT_MISMATCH");
        }
    }
}
