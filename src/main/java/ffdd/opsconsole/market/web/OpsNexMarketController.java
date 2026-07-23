package ffdd.opsconsole.market.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.application.G2G3AdminCommandService;
import ffdd.opsconsole.market.application.G4AdminCommandService;
import ffdd.opsconsole.market.application.OpsRepurchaseAdminService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/nex")
@RequiredArgsConstructor
public class OpsNexMarketController {
    private final OpsNexMarketService marketService;
    private final G2G3AdminCommandService commandService;
    private final G4AdminCommandService g4CommandService;
    private final OpsRepurchaseAdminService repurchaseAdminService;

    @GetMapping("/curve")
    @PreAuthorize("hasAuthority('finprod_g3_read')")
    public ApiResult<Map<String, Object>> curve() {
        return marketService.overview();
    }

    @GetMapping("/curve/history")
    @PreAuthorize("hasAuthority('finprod_g3_read')")
    public ApiResult<Map<String, Object>> curveHistory() {
        return marketService.curveHistory();
    }

    @GetMapping("/repurchase")
    @PreAuthorize("hasAuthority('finprod_g7_read')")
    public ApiResult<Map<String, Object>> repurchase() {
        return marketService.repurchaseOverview();
    }

    @GetMapping("/genesis")
    @PreAuthorize("hasAuthority('finprod_g4_read')")
    public ApiResult<Map<String, Object>> genesis(
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        return marketService.genesisOverview(page, pageSize);
    }

    @PutMapping("/curve")
    @PreAuthorize("hasAnyAuthority('finprod_g3_curve_target_price_write','finprod_g3_curve_pump_prob_write','finprod_g3_write')")
    public ApiResult<Map<String, Object>> updateCurve(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketCurveUpdateRequest request) {
        return commandService == null ? marketService.updateWeeklyCurve(idempotencyKey, request)
                : commandService.weeklyCurve(idempotencyKey, request);
    }

    @PostMapping("/curve/advance")
    @PreAuthorize("hasAuthority('finprod_g3_write')")
    public ApiResult<Map<String, Object>> advance(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketAdvanceRequest request) {
        return commandService == null ? marketService.advanceCurrentFrame(idempotencyKey, request)
                : commandService.advance(idempotencyKey, request);
    }

    @PatchMapping("/curve/controls/{controlKey}")
    @PreAuthorize("hasAuthority('finprod_g3_write')")
    public ApiResult<Map<String, Object>> updateControl(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String controlKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return commandService == null ? marketService.updateControl(idempotencyKey, controlKey, request)
                : commandService.control(idempotencyKey, controlKey, request);
    }

    @PatchMapping("/overrides/{overrideKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g3_override_price_write','finprod_g3_engine_pause_toggle','finprod_g3_write')"
            + " and (#overrideKey != 'oracle' or @superAdminAuthorization.isSuperAdmin(authentication))"
            + " and (#overrideKey != 'paused' or #request.value == 'true' or @superAdminAuthorization.isSuperAdmin(authentication))")
    public ApiResult<Map<String, Object>> updateOverride(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String overrideKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return commandService == null ? marketService.updateOverride(idempotencyKey, overrideKey, request)
                : commandService.override(idempotencyKey, overrideKey, request);
    }

    @PatchMapping("/repurchase/params/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g7_apy_write','finprod_g7_nurture_write','finprod_g7_write')")
    public ApiResult<Map<String, Object>> updateRepurchaseParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return repurchaseAdminService == null
                ? marketService.updateRepurchaseParam(idempotencyKey, paramKey, request)
                : repurchaseAdminService.update(paramKey, idempotencyKey,
                        new OpsRepurchaseAdminService.UpdateRequest(
                                request.value(), request.reason(), request.operator(), request.decisionRef()));
    }

    @PatchMapping("/genesis/params/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g4_write','finprod_g4_price_write','finprod_g4_dividend_rate_write','finprod_g4_royalty_write','finprod_g4_airdrop_pct_write','finprod_g4_emission_curve_write','finprod_g4_airdrop_lock_days_write')")
    public ApiResult<Map<String, Object>> updateGenesisParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return g4CommandService == null ? marketService.updateGenesisParam(idempotencyKey, paramKey, request)
                : g4CommandService.updateParam(paramKey, idempotencyKey, request);
    }

    @PatchMapping("/genesis/market-status")
    @PreAuthorize("hasAuthority('finprod_g4_market_toggle')")
    public ApiResult<Map<String, Object>> updateGenesisMarketStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return g4CommandService == null ? marketService.updateGenesisMarketStatus(idempotencyKey, request)
                : g4CommandService.pauseMarket(idempotencyKey, request);
    }

    @PostMapping("/genesis/dividend-batches/{batchNo}/rerun")
    @PreAuthorize("hasAuthority('finprod_g4_write')")
    public ApiResult<Map<String, Object>> rerunGenesisDividendBatch(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String batchNo,
            @RequestBody NexMarketValueUpdateRequest request) {
        return g4CommandService == null ? marketService.rerunGenesisDividendBatch(idempotencyKey, batchNo, request)
                : g4CommandService.rerunEmission(idempotencyKey, batchNo, request);
    }
}
