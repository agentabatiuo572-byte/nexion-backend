package ffdd.opsconsole.team.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.VRankReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OpsVRankReviewController {
    private final VRankReviewService reviewService;

    @GetMapping(OpsAdminApi.ADMIN_PREFIX + "/teams/v8-review-candidates")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<VRankReviewService.ReviewPage> list(@RequestParam(required = false) String cursor) {
        return ApiResult.ok(reviewService.list(cursor));
    }
}
