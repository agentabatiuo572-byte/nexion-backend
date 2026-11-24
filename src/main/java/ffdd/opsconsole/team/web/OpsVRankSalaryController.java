package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.VRankSalaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OpsVRankSalaryController {
    private final VRankSalaryService salaries;

    @GetMapping("/api/admin/teams/ranks/salary/claims")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<VRankSalaryService.AdminClaims> claims(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String userNo,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String rankCode,
            @RequestParam(required = false) Long cursor) {
        return ApiResult.ok(salaries.claims(userNo == null ? userId : userNo, month, rankCode, cursor));
    }
}
