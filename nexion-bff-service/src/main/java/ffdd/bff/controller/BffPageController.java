package ffdd.bff.controller;

import ffdd.bff.dto.BffSnapshot;
import ffdd.bff.service.BffAggregationService;
import ffdd.common.api.ApiResult;
import ffdd.common.security.AuthHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff")
public class BffPageController {
    private final BffAggregationService aggregationService;

    public BffPageController(BffAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/home")
    public ApiResult<BffSnapshot> home(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(aggregationService.home(userId));
    }

    @GetMapping("/earn")
    public ApiResult<BffSnapshot> earn(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(aggregationService.earn(userId));
    }

    @GetMapping("/wallet")
    public ApiResult<BffSnapshot> wallet(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(aggregationService.wallet(userId));
    }

    @GetMapping("/team")
    public ApiResult<BffSnapshot> team(@RequestHeader(AuthHeaders.SUBJECT_ID) Long userId) {
        return ApiResult.ok(aggregationService.team(userId));
    }
}
