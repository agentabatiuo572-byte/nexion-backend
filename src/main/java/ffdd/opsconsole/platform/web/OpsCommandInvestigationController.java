package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminCommandInvestigationService;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/commands/investigations")
@RequiredArgsConstructor
public class OpsCommandInvestigationController {
    private final AdminCommandInvestigationService service;

    @GetMapping("/by-command-key")
    public ApiResult<List<AdminCommandInvestigationService.Receipt>> discover(
            @RequestParam String commandKey, @RequestParam String originalMethod, @RequestParam String originalPath) {
        return ApiResult.ok(service.discover(commandKey, originalMethod, originalPath));
    }

    @GetMapping
    public ApiResult<AdminCommandInvestigationService.Receipt> read(
            @RequestParam String scope, @RequestParam String commandKey, @RequestParam(required = false) String requestHash) {
        return ApiResult.ok(service.read(scope, commandKey, requestHash));
    }

    @PostMapping
    public ApiResult<AdminCommandInvestigationService.Receipt> investigate(
            @RequestBody AdminCommandInvestigationService.Request request) {
        return ApiResult.ok(service.investigate(request));
    }
}
