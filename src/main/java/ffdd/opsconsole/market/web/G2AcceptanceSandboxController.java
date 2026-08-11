package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.G2AcceptanceSandboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Acceptance-only controller; production has no route to this executor. */
@RestController
@Profile({"acceptance", "test"})
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/exchange/acceptance")
@RequiredArgsConstructor
public class G2AcceptanceSandboxController {
    private final G2AcceptanceSandboxService service;

    @GetMapping
    @PreAuthorize("hasAuthority('finprod_g2_read')")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(service.overview());
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('finprod_g2_write')")
    public ApiResult<Map<String, Object>> generate() {
        return ApiResult.ok(service.generate());
    }

    @PostMapping("/batches/{batchNo}/process")
    @PreAuthorize("hasAuthority('finprod_g2_write')")
    public ApiResult<Map<String, Object>> process(
            @PathVariable String batchNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 160) {
            return ApiResult.fail(422, "G2_ACCEPTANCE_SANDBOX_IDEMPOTENCY_KEY_REQUIRED");
        }
        return ApiResult.ok(service.process(batchNo, idempotencyKey.trim()));
    }

    @DeleteMapping("/batches/{batchNo}")
    @PreAuthorize("hasAuthority('finprod_g2_write')")
    public ApiResult<Void> cleanup(@PathVariable String batchNo) {
        service.cleanup(batchNo);
        return ApiResult.ok();
    }
}
