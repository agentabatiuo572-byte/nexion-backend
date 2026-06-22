package ffdd.opsconsole.platform.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsAdminCommandService;
import ffdd.opsconsole.platform.dto.AdminCommandRequest;
import ffdd.opsconsole.platform.dto.AdminCommandResponse;
import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/commands")
@RequiredArgsConstructor
public class OpsAdminCommandController {
    private final OpsAdminCommandService commandService;

    @PostMapping
    public ApiResult<AdminCommandResponse> accept(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) AdminCommandRequest request) {
        return commandService.accept(idempotencyKey, request);
    }
}
