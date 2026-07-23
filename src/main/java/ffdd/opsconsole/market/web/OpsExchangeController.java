package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.application.G2G3AdminCommandService;
import ffdd.opsconsole.market.dto.ExchangeKycReviewRequest;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueBatchRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/exchange")
@RequiredArgsConstructor
public class OpsExchangeController {
    private final OpsNexMarketService marketService;
    private final G2G3AdminCommandService commandService;

    @GetMapping
    @PreAuthorize("hasAuthority('finprod_g2_read')")
    public ApiResult<Map<String, Object>> overview() {
        return marketService.exchangeOverview();
    }

    @GetMapping("/orders/{exchangeNo}")
    @PreAuthorize("hasAuthority('finprod_g2_read')")
    public ApiResult<Map<String, Object>> orderDetail(@PathVariable String exchangeNo) {
        return marketService.exchangeOrderDetail(exchangeNo);
    }

    @PatchMapping("/params/{paramKey}")
    @PreAuthorize("hasAnyAuthority('finprod_g2_cap_user_write','finprod_g2_cap_platform_write','finprod_g2_fee_rate_write','finprod_g2_write')")
    public ApiResult<Map<String, Object>> updateParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody ExchangeParamUpdateRequest request) {
        return commandService == null ? marketService.updateExchangeParam(idempotencyKey, paramKey, request)
                : commandService.exchangeParam(idempotencyKey, paramKey, request);
    }

    @PatchMapping("/swap")
    @PreAuthorize("hasAuthority('finprod_g2_swap_toggle')")
    public ApiResult<Map<String, Object>> updateSwapStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ExchangeSwapStatusRequest request) {
        return commandService == null ? marketService.updateExchangeSwapStatus(idempotencyKey, request)
                : commandService.pauseExchange(idempotencyKey, request);
    }

    @PostMapping("/queue/{exchangeNo}/cancel")
    @PreAuthorize("hasAuthority('finprod_g2_queue_cancel')")
    public ApiResult<Map<String, Object>> cancelQueueOrder(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String exchangeNo,
            @RequestBody ExchangeQueueCancelRequest request) {
        return commandService == null ? marketService.cancelExchangeQueueOrder(idempotencyKey, exchangeNo, request)
                : commandService.cancelExchange(idempotencyKey, exchangeNo, request);
    }

    @PostMapping("/queue/{exchangeNo}/kyc-review")
    @PreAuthorize("hasAuthority('finprod_g2_write')")
    public ApiResult<Map<String, Object>> triggerKycReview(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String exchangeNo,
            @RequestBody ExchangeKycReviewRequest request) {
        return commandService == null ? marketService.triggerExchangeKycReview(idempotencyKey, exchangeNo, request)
                : commandService.kycReview(idempotencyKey, exchangeNo, request);
    }

    @PostMapping("/queue/process")
    @PreAuthorize("hasAuthority('finprod_g2_write')")
    public ApiResult<Map<String,Object>> processQueue(
            @RequestHeader(value=OpsAdminApi.IDEMPOTENCY_KEY_HEADER,required=false) String idempotencyKey,
            @RequestBody ExchangeQueueBatchRequest request) {
        if (commandService == null) return ApiResult.fail(503,"G2_COMMAND_BOUNDARY_UNAVAILABLE");
        return commandService.processQueue(idempotencyKey,request);
    }
}
