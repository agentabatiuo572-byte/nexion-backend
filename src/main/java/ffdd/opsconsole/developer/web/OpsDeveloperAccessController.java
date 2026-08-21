package ffdd.opsconsole.developer.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.developer.application.OpsDeveloperAccessService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/developer/access-requests")
@RequiredArgsConstructor
public class OpsDeveloperAccessController {
    private final OpsDeveloperAccessService service;

    @GetMapping
    // This is a global data scope: only the explicit governance grant or the built-in
    // super-admin role can see requests. UI visibility is not used as an authorization gate.
    @PreAuthorize("hasAnyAuthority('developer_access_read','ROLE_SUPER_ADMIN')")
    public ApiResult<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceEnvironment) {
        return service.page(pageNum, pageSize, status, keyword, sourceEnvironment);
    }

    @PostMapping("/{requestNo}/approve")
    @PreAuthorize("hasAnyAuthority('developer_access_approve','ROLE_SUPER_ADMIN')")
    public ApiResult<Map<String, Object>> approve(
            @PathVariable String requestNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ReviewRequest request, Authentication authentication) {
        return service.approve(requestNo, request == null ? null : request.expectedStatus(),
                request == null ? null : request.reason(), operator(authentication), idempotencyKey);
    }

    @PostMapping("/{requestNo}/reject")
    @PreAuthorize("hasAnyAuthority('developer_access_reject','ROLE_SUPER_ADMIN')")
    public ApiResult<Map<String, Object>> reject(
            @PathVariable String requestNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ReviewRequest request, Authentication authentication) {
        return service.reject(requestNo, request == null ? null : request.expectedStatus(),
                request == null ? null : request.reason(), operator(authentication), idempotencyKey);
    }

    @PostMapping("/{requestNo}/revoke")
    @PreAuthorize("hasAnyAuthority('developer_access_revoke','ROLE_SUPER_ADMIN')")
    public ApiResult<Map<String, Object>> revoke(
            @PathVariable String requestNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ReviewRequest request, Authentication authentication) {
        return service.revoke(requestNo, request == null ? null : request.expectedStatus(),
                request == null ? null : request.reason(), operator(authentication), idempotencyKey);
    }

    private String operator(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? null : authentication.getName();
    }

    public record ReviewRequest(String expectedStatus, String reason) { }
}
