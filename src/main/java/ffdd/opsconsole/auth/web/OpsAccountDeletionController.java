package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AccountDeletionAdminService;
import ffdd.opsconsole.auth.dto.AccountDeletionAdminView;
import ffdd.opsconsole.auth.dto.AdminAccountDeletionCommandRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/users/account-deletions")
@RequiredArgsConstructor
public class OpsAccountDeletionController {
    private final AccountDeletionAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('user_c1_read')")
    public ApiResult<List<AccountDeletionAdminView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(service.list(status, page, limit));
    }

    @GetMapping("/{requestNo}")
    @PreAuthorize("hasAuthority('user_c1_read')")
    public ApiResult<AccountDeletionAdminView> get(@PathVariable String requestNo) {
        return ApiResult.ok(service.find(requestNo));
    }

    @PostMapping("/{requestNo}/review")
    @PreAuthorize("hasAuthority('user_c1_write')")
    public ApiResult<AccountDeletionAdminView> review(
            @PathVariable String requestNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdminAccountDeletionCommandRequest request) {
        return ApiResult.ok(service.review(requestNo, idempotencyKey, request));
    }

    @PostMapping("/{requestNo}/block")
    @PreAuthorize("hasAuthority('user_c1_write')")
    public ApiResult<AccountDeletionAdminView> block(
            @PathVariable String requestNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdminAccountDeletionCommandRequest request) {
        return ApiResult.ok(service.block(requestNo, idempotencyKey, request));
    }

    @PostMapping("/{requestNo}/complete")
    @PreAuthorize("hasAuthority('user_c1_write')")
    public ApiResult<AccountDeletionAdminView> complete(
            @PathVariable String requestNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdminAccountDeletionCommandRequest request) {
        return ApiResult.ok(service.complete(requestNo, idempotencyKey, request));
    }

    @PostMapping("/{requestNo}/cancel")
    @PreAuthorize("hasAuthority('user_c1_write')")
    public ApiResult<AccountDeletionAdminView> cancel(
            @PathVariable String requestNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdminAccountDeletionCommandRequest request) {
        return ApiResult.ok(service.cancel(requestNo, idempotencyKey, request));
    }
}
