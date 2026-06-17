package ffdd.opsconsole.market.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/market/nex")
public class OpsNexMarketController {
    private final OpsNexMarketService marketService;

    public OpsNexMarketController(OpsNexMarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping("/curve")
    public ApiResult<Map<String, Object>> curve() {
        return marketService.overview();
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
}
