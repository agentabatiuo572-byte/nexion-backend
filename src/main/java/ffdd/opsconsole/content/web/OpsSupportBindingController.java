package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(OpsAdminApi.ADMIN_PREFIX+"/content/support-agents")
public class OpsSupportBindingController {
    private final SupportBindingService service;
    private final ProductionSupportPathGuard production;
    private final SupportBindingRandomService random;

    @GetMapping("/rules") @PreAuthorize("hasAuthority('service_m1_read')")
    public ApiResult<SupportRules> rules(){return ApiResult.ok(service.rules());}

    @PutMapping("/rules") @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<SupportRules> rules(@RequestHeader("Idempotency-Key") String key,@RequestBody SupportRulesRequest request){
        production.requireOpsWriteAllowed();return service.updateRules(key,request);
    }

    @GetMapping("/binding-pool") @PreAuthorize("hasAuthority('service_m1_read')")
    public ApiResult<ffdd.opsconsole.shared.api.PageResult<Map<String,Object>>> pool(@RequestParam(required=false) String keyword,@RequestParam(required=false) String reason,
            @RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize){
        return ApiResult.ok(service.pool(keyword,reason,pageNum,pageSize));
    }

    @GetMapping("/handover-customers") @PreAuthorize("hasAuthority('service_m1_read')")
    public ApiResult<ffdd.opsconsole.shared.api.PageResult<Map<String,Object>>> handover(@RequestParam(required=false) Long agentAdminId,
            @RequestParam(defaultValue="true") boolean unavailableOnly,@RequestParam(defaultValue="1") long pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResult.ok(service.handover(agentAdminId,unavailableOnly,pageNum,pageSize));
    }

    @PostMapping("/assignments/transfer") @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<List<SupportAssignment>> transfer(@RequestHeader("Idempotency-Key") String key,@RequestBody SupportBindingRequest request){
        production.requireOpsWriteAllowed();return service.transfer(key,request);
    }

    @PostMapping("/assignments/random-preview") @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<SupportRandom.Preview> randomPreview(@RequestBody SupportRandomRequest.Preview request) {
        production.requireOpsWriteAllowed();return ApiResult.ok(random.preview(request));
    }

    @PostMapping("/assignments/random") @PreAuthorize("hasAuthority('service_m1_write')")
    public ApiResult<SupportRandom.Result> random(@RequestHeader("Idempotency-Key") String key,@RequestBody SupportRandomRequest.Confirm request) {
        production.requireOpsWriteAllowed();return random.confirm(key,request);
    }
}
