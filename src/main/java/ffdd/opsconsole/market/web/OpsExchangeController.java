package ffdd.opsconsole.market.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.ExchangeKycReviewRequest;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public ApiResult<Map<String, Object>> overview() {
        return marketService.exchangeOverview();
    }

    @GetMapping("/orders/{exchangeNo}")
    public ApiResult<Map<String, Object>> orderDetail(@PathVariable String exchangeNo) {
        return marketService.exchangeOrderDetail(exchangeNo);
    }

    @PatchMapping("/params/{paramKey}")
    public ApiResult<Map<String, Object>> updateParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody ExchangeParamUpdateRequest request) {
        return marketService.updateExchangeParam(idempotencyKey, paramKey, request);
    }

    @PatchMapping("/swap")
    public ApiResult<Map<String, Object>> updateSwapStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody ExchangeSwapStatusRequest request) {
        return marketService.updateExchangeSwapStatus(idempotencyKey, request);
    }

    @PostMapping("/queue/{exchangeNo}/cancel")
    public ApiResult<Map<String, Object>> cancelQueueOrder(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String exchangeNo,
            @RequestBody ExchangeQueueCancelRequest request) {
        return marketService.cancelExchangeQueueOrder(idempotencyKey, exchangeNo, request);
    }

    @PostMapping("/queue/{exchangeNo}/kyc-review")
    public ApiResult<Map<String, Object>> triggerKycReview(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String exchangeNo,
            @RequestBody ExchangeKycReviewRequest request) {
        return marketService.triggerExchangeKycReview(idempotencyKey, exchangeNo, request);
    }
}
