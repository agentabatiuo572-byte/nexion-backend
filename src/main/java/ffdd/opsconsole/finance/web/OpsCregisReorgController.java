package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.cregis.CregisReorgResolutionService;
import ffdd.opsconsole.finance.cregis.CregisReorgResolutionService.*;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/finance/cregis/reorg")
public class OpsCregisReorgController {
    private final CregisReorgResolutionService service;
    @GetMapping("/incidents") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> incidents(@RequestParam(defaultValue="0") long beforeId,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(service.incidents(beforeId,limit));}
    @GetMapping("/proposals") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> proposals(@RequestParam(defaultValue="0") long beforeId,@RequestParam(defaultValue="20") int limit){return ApiResult.ok(service.proposals(beforeId,limit));}
    @GetMapping("/incidents/{id}/evidence") @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<Map<String,Object>> evidence(@PathVariable long id,@RequestParam Action action){return ApiResult.ok(service.evidence(id,action));}
    @PostMapping("/incidents/{id}/proposals") @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Result> propose(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Proposal request){return ApiResult.ok(service.propose(id,key,request));}
    @PostMapping("/proposals/{id}/decision") @PreAuthorize("hasAuthority('finance_d1_channel_manage')")
    public ApiResult<Result> decide(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Decision request){return ApiResult.ok(service.decide(id,key,request));}
}
