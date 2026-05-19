package ffdd.team.controller;

import ffdd.common.api.ApiResult;
import ffdd.team.domain.CommissionEvent;
import ffdd.team.dto.BinaryCommissionRequest;
import ffdd.team.dto.CommissionResult;
import ffdd.team.dto.CultivationCommissionRequest;
import ffdd.team.dto.LeadershipCommissionRequest;
import ffdd.team.dto.PeerCommissionRequest;
import ffdd.team.dto.UnilevelCommissionRequest;
import ffdd.team.service.CommissionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/team/commissions")
@RequiredArgsConstructor
public class CommissionController {
    private final CommissionService commissionService;

    @PostMapping("/unilevel")
    public ApiResult<CommissionResult> settleUnilevel(@Valid @RequestBody UnilevelCommissionRequest request) {
        return ApiResult.ok(commissionService.settleUnilevel(request));
    }

    @PostMapping("/binary")
    public ApiResult<CommissionResult> settleBinary(@Valid @RequestBody BinaryCommissionRequest request) {
        return ApiResult.ok(commissionService.settleBinary(request));
    }

    @PostMapping("/peer")
    public ApiResult<CommissionResult> settlePeer(@Valid @RequestBody PeerCommissionRequest request) {
        return ApiResult.ok(commissionService.settlePeer(request));
    }

    @PostMapping("/cultivation")
    public ApiResult<CommissionResult> settleCultivation(@Valid @RequestBody CultivationCommissionRequest request) {
        return ApiResult.ok(commissionService.settleCultivation(request));
    }

    @PostMapping("/leadership")
    public ApiResult<CommissionResult> settleLeadership(@Valid @RequestBody LeadershipCommissionRequest request) {
        return ApiResult.ok(commissionService.settleLeadership(request));
    }

    @GetMapping
    public ApiResult<List<CommissionEvent>> listMine(@RequestParam Long userId) {
        return ApiResult.ok(commissionService.listMine(userId));
    }
}

