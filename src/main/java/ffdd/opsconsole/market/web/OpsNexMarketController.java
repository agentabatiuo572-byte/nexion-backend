package ffdd.opsconsole.market.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/nex")
@RequiredArgsConstructor
public class OpsNexMarketController {
    private final OpsNexMarketService marketService;

    @GetMapping("/curve")
    public ApiResult<Map<String, Object>> curve() {
        return marketService.overview();
    }

    @GetMapping("/curve/history")
    public ApiResult<Map<String, Object>> curveHistory() {
        return marketService.curveHistory();
    }

    @GetMapping("/repurchase")
    public ApiResult<Map<String, Object>> repurchase() {
        return marketService.repurchaseOverview();
    }

    @GetMapping("/genesis")
    public ApiResult<Map<String, Object>> genesis() {
        return marketService.genesisOverview();
    }

    @PutMapping("/curve")
    public ApiResult<Map<String, Object>> updateCurve(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketCurveUpdateRequest request) {
        return marketService.updateWeeklyCurve(idempotencyKey, request);
    }

    @PostMapping("/curve/advance")
    public ApiResult<Map<String, Object>> advance(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketAdvanceRequest request) {
        return marketService.advanceCurrentFrame(idempotencyKey, request);
    }

    @PatchMapping("/curve/controls/{controlKey}")
    public ApiResult<Map<String, Object>> updateControl(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String controlKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateControl(idempotencyKey, controlKey, request);
    }

    @PatchMapping("/overrides/{overrideKey}")
    public ApiResult<Map<String, Object>> updateOverride(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String overrideKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateOverride(idempotencyKey, overrideKey, request);
    }

    @PatchMapping("/repurchase/params/{paramKey}")
    public ApiResult<Map<String, Object>> updateRepurchaseParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateRepurchaseParam(idempotencyKey, paramKey, request);
    }

    @PatchMapping("/genesis/params/{paramKey}")
    public ApiResult<Map<String, Object>> updateGenesisParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateGenesisParam(idempotencyKey, paramKey, request);
    }

    @PatchMapping("/genesis/market-status")
    public ApiResult<Map<String, Object>> updateGenesisMarketStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.updateGenesisMarketStatus(idempotencyKey, request);
    }

    @PostMapping("/genesis/dividend-batches/{batchNo}/rerun")
    public ApiResult<Map<String, Object>> rerunGenesisDividendBatch(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String batchNo,
            @RequestBody NexMarketValueUpdateRequest request) {
        return marketService.rerunGenesisDividendBatch(idempotencyKey, batchNo, request);
    }
}
