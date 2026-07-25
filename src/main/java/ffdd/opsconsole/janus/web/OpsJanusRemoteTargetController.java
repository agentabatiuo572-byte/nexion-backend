package ffdd.opsconsole.janus.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.janus.application.OpsJanusRemoteTargetService;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetCreateRequest;
import ffdd.opsconsole.janus.dto.JanusRemoteTargetDisableRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/janus/remote-targets")
@RequiredArgsConstructor
public class OpsJanusRemoteTargetController {
    private final OpsJanusRemoteTargetService service;

    @GetMapping
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<List<JanusRemoteTargetView>> list() {
        return service.list();
    }

    @GetMapping("/origins")
    @PreAuthorize("hasAuthority('risk_k6_read')")
    public ApiResult<List<String>> allowedOrigins() {
        return service.allowedOrigins();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('risk_k6_target_manage')")
    public ApiResult<JanusRemoteTargetView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusRemoteTargetCreateRequest request) {
        return service.create(idempotencyKey, request);
    }

    @PostMapping("/{key}/{version}/disable")
    @PreAuthorize("hasAuthority('risk_k6_target_manage')")
    public ApiResult<JanusRemoteTargetView> disable(
            @PathVariable String key,
            @PathVariable int version,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JanusRemoteTargetDisableRequest request) {
        return service.disable(key, version, idempotencyKey, request);
    }
}
