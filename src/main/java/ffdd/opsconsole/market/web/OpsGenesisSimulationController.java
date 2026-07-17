package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsGenesisSimulationService;
import ffdd.opsconsole.market.dto.GenesisSimulationRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/nex/genesis/operations")
@RequiredArgsConstructor
public class OpsGenesisSimulationController {
    private final OpsGenesisSimulationService service;

    @GetMapping
    @PreAuthorize("hasAuthority('finprod_g4_read')")
    public ApiResult<Map<String, Object>> overview() {
        return ApiResult.ok(service.overview());
    }

    @PatchMapping("/config/{configKey}")
    @PreAuthorize("hasAuthority('finprod_g4_write')")
    public ApiResult<Map<String, Object>> updateConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @PathVariable String configKey, @RequestBody NexMarketValueUpdateRequest request) {
        return ApiResult.ok(service.updateConfig(configKey, key, request));
    }

    @PostMapping("/simulations")
    @PreAuthorize("hasAuthority('finprod_g4_write')")
    public ApiResult<Map<String, Object>> create(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @RequestBody GenesisSimulationRequest request) {
        return ApiResult.ok(service.create(key, request));
    }

    @DeleteMapping("/simulations/{id}")
    @PreAuthorize("hasAuthority('finprod_g4_write')")
    public ApiResult<Map<String, Object>> archive(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String key,
            @PathVariable Long id, @RequestBody NexMarketValueUpdateRequest request) {
        return ApiResult.ok(service.archive(id, key, request));
    }
}
