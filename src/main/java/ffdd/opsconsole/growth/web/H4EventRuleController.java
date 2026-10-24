package ffdd.opsconsole.growth.web;

import ffdd.opsconsole.growth.application.H4EventRuleService;
import ffdd.opsconsole.growth.application.OpsGrowthCommandBoundary;
import ffdd.opsconsole.growth.dto.H4EventRuleRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/growth/event-rules")
@RequiredArgsConstructor
public class H4EventRuleController {
    private final H4EventRuleService service;
    private final OpsGrowthCommandBoundary commands;
    @GetMapping
    @PreAuthorize("hasAuthority('growth_h4_read')")
    public ApiResult<Map<String,Object>> list() { return ApiResult.ok(Map.of("rules",service.overview())); }
    @PatchMapping("/{eventCode}")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String,Object>> update(@PathVariable String eventCode,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody H4EventRuleRequest request) {
        return commands.execute("H4","EVENT_RULE_UPDATE",eventCode,key,request,()->service.update(eventCode,request));
    }
}
