package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.AppLearningService;
import ffdd.opsconsole.content.domain.AppLearningCourseView;
import ffdd.opsconsole.content.domain.AppLearningOverview;
import ffdd.opsconsole.content.domain.AppLearningQuizResult;
import ffdd.opsconsole.content.domain.LearningQuizReceipt;
import ffdd.opsconsole.content.dto.AppLearningQuizSubmitRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/content/learning")
@RequiredArgsConstructor
public class AppLearningController {
    private final AppLearningService service;

    @GetMapping("/courses")
    public ApiResult<AppLearningOverview> courses(@RequestParam(defaultValue = "vi") String language, Authentication auth) {
        return service.overview(userId(auth), language);
    }

    @GetMapping("/courses/{courseId}")
    public ApiResult<AppLearningCourseView> course(@PathVariable String courseId,
            @RequestParam(defaultValue = "vi") String language, Authentication auth) {
        return service.course(userId(auth), courseId, language);
    }

    @PostMapping("/courses/{courseId}/start")
    public ApiResult<AppLearningCourseView> start(@PathVariable String courseId,
            @RequestParam(defaultValue = "vi") String language,
            @RequestParam(value = "version", required = false) String expectedVersion,
            Authentication auth) {
        if (!StringUtils.hasText(expectedVersion)) return ApiResult.fail(422, "LEARNING_COURSE_VERSION_REQUIRED");
        return service.start(userId(auth), courseId, language, expectedVersion);
    }

    @PostMapping("/courses/{courseId}/complete")
    public ApiResult<AppLearningQuizResult> complete(@PathVariable String courseId,
            @RequestParam(value = "version", required = false) String expectedVersion,
            Authentication auth) {
        if (!StringUtils.hasText(expectedVersion)) return ApiResult.fail(422, "LEARNING_COURSE_VERSION_REQUIRED");
        return service.complete(userId(auth), courseId, expectedVersion);
    }

    @PostMapping("/courses/{courseId}/quiz")
    public ApiResult<AppLearningQuizResult> quiz(@PathVariable String courseId,
            @RequestBody(required = false) AppLearningQuizSubmitRequest request,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication auth) {
        AppLearningQuizSubmitRequest canonicalRequest = request == null
                ? null
                : new AppLearningQuizSubmitRequest(request.answers(), idempotencyKey, request.expectedVersion());
        if (canonicalRequest == null || !StringUtils.hasText(canonicalRequest.expectedVersion())) {
            return ApiResult.fail(422, "LEARNING_COURSE_VERSION_REQUIRED");
        }
        return service.submitQuiz(userId(auth), courseId, canonicalRequest);
    }

    @GetMapping("/courses/{courseId}/quiz/receipts/{idempotencyKey}")
    public ApiResult<LearningQuizReceipt> quizReceipt(@PathVariable String courseId, @PathVariable String idempotencyKey,
            @RequestParam("version") String expectedVersion, Authentication auth) {
        return service.quizReceipt(userId(auth), courseId, expectedVersion, idempotencyKey);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) return null;
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
