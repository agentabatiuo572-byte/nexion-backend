package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.ProductionSupportPathGuard;
import ffdd.opsconsole.content.application.SupportLeaderboardService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content/support-workbench/leaderboard")
public class SupportLeaderboardController {
    private final SupportLeaderboardService service;
    private final ProductionSupportPathGuard guard;
    public SupportLeaderboardController(SupportLeaderboardService service,ProductionSupportPathGuard guard){this.service=service;this.guard=guard;}
    @GetMapping
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> page(@RequestParam MultiValueMap<String,String> parameters){guard.requireOpsWriteAllowed();return ApiResult.ok(service.page(parameters));}
    @GetMapping("/{agentId}")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ApiResult<Map<String,Object>> detail(@PathVariable long agentId,@RequestParam MultiValueMap<String,String> parameters){guard.requireOpsWriteAllowed();return ApiResult.ok(service.detail(parameters,agentId));}
    @GetMapping("/{agentId}/avatar")
    @PreAuthorize("hasAnyAuthority('service_m1_read','service_m3_read')")
    public ResponseEntity<byte[]> avatar(@PathVariable long agentId,@RequestParam MultiValueMap<String,String> parameters){guard.requireOpsWriteAllowed();return OpsSupportAdminAvatarController.image(service.avatar(parameters,agentId));}
}
