package ffdd.opsconsole.device.web;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.dto.AppComputeReceiptPage;
import ffdd.opsconsole.device.dto.AppComputeReceiptView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.dto.AppTaskAssignmentsResponse;
import ffdd.opsconsole.device.dto.AppTaskClaimRequest;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppTaskAssignmentController {
    private final AppTaskAssignmentService service;

    @GetMapping("/api/tasks/assignments")
    public ApiResult<AppTaskAssignmentsResponse> assignments(Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED") : service.assignments(userId);
    }

    @PostMapping("/api/tasks/assignments/claim")
    public ApiResult<AppTaskAssignmentView> claim(
            @RequestBody(required = false) AppTaskClaimRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.claim(userId, idempotencyKey, request);
    }

    @GetMapping("/api/tasks/receipts/{receiptNo}")
    public ApiResult<AppComputeReceiptView> receipt(
            @PathVariable String receiptNo,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.receipt(userId, receiptNo);
    }

    @GetMapping("/api/tasks/receipts")
    public ApiResult<AppComputeReceiptPage> receipts(
            @RequestParam(required = false, defaultValue = "0") String offset,
            @RequestParam(required = false, defaultValue = "20") String limit,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.receipts(userId, pageValue(offset), pageValue(limit));
    }

    @PostMapping("/api/tasks/assignments/{taskNo}/complete")
    public ApiResult<AppTaskAssignmentView> complete(
            @PathVariable String taskNo,
            @RequestBody(required = false) AppTaskCompleteRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long userId = userId(authentication);
        return userId == null ? ApiResult.fail(403, "USER_SUBJECT_REQUIRED")
                : service.complete(userId, taskNo, idempotencyKey, request);
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

    private int pageValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[0-9]{1,10}")) throw new BizException(422, "TASK_RECEIPT_PAGE_INVALID");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new BizException(422, "TASK_RECEIPT_PAGE_INVALID");
        }
    }
}
