package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/growth")
public class OpsGrowthController {
    private final OpsGrowthService growthService;

    public OpsGrowthController(OpsGrowthService growthService) {
        this.growthService = growthService;
    }

    @GetMapping("/phases")
    public ApiResult<Map<String, Object>> phases() {
        return growthService.phases();
    }

    @PatchMapping("/phases/dials/{dialKey}")
    public ApiResult<Map<String, Object>> updatePhaseDial(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String dialKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePhaseDial(idempotencyKey, dialKey, request);
    }

    @GetMapping("/check-in")
    public ApiResult<Map<String, Object>> checkIn() {
        return growthService.checkIn();
    }

    @PatchMapping("/check-in")
    public ApiResult<Map<String, Object>> updateCheckIn(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateCheckIn(idempotencyKey, request);
    }

    @GetMapping("/withdraw-gate")
    public ApiResult<Map<String, Object>> withdrawGate() {
        return growthService.withdrawGate();
    }

    @PatchMapping("/withdraw-gate")
    public ApiResult<Map<String, Object>> updateWithdrawGate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateWithdrawGate(idempotencyKey, request);
    }
}
