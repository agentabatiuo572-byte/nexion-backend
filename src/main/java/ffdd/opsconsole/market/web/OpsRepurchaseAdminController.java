package ffdd.opsconsole.market.web;

import ffdd.opsconsole.market.application.OpsRepurchaseAdminService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/admin/repurchase")
@RequiredArgsConstructor
public class OpsRepurchaseAdminController {
    private final OpsRepurchaseAdminService service;

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('finprod_g7_read')")
    public ApiResult<Map<String, Object>> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return service.orders(status, cursor, limit);
    }

    @PutMapping("/config/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g7_apy_write','finprod_g7_nurture_write','finprod_g7_write')")
    public ApiResult<Map<String, Object>> update(@PathVariable String paramKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OpsRepurchaseAdminService.UpdateRequest request) {
        return service.update(paramKey, idempotencyKey, request);
    }
}
