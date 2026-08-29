package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisGatewayException;
import ffdd.opsconsole.finance.cregis.CregisSandboxService;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test")
@RequestMapping("/api/admin/finance/cregis/sandbox")
@RequiredArgsConstructor
public class CregisSandboxController {
    private final CregisSandboxService service;

    @GetMapping
    @PreAuthorize("hasAuthority('finance_d1_read')")
    public ApiResult<CregisSandboxService.SandboxOverview> overview() {
        return ApiResult.ok(service.overview());
    }

    @PostMapping("/probes")
    @PreAuthorize("hasAuthority('finance_d1_bank_config_manage')")
    public ResponseEntity<ApiResult<CregisSandboxService.ProbeResult>> probe() {
        try {
            return ResponseEntity.ok(ApiResult.ok(service.runProbe()));
        } catch (CregisGatewayException failure) {
            HttpStatus status = failure.kind() == CregisGatewayException.Kind.CONFIGURATION
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status)
                    .body(ApiResult.fail(status.value(), failure.getMessage()));
        }
    }
}
