package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.SupportPersonalPhraseService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-workbench/phrases")
public class SupportPersonalPhraseController {
    private final SupportPersonalPhraseService service;

    public record PhraseRequest(String content) {}

    @GetMapping @PreAuthorize("hasAnyAuthority('service_m3_read','service_m3_write')")
    public ApiResult<List<Map<String, Object>>> list() { return ApiResult.ok(service.list()); }

    @PostMapping @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<List<Map<String, Object>>> add(@RequestBody PhraseRequest request) {
        return ApiResult.ok(service.add(request == null ? null : request.content()));
    }

    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('service_m3_write')")
    public ApiResult<List<Map<String, Object>>> delete(@PathVariable Long id) { return ApiResult.ok(service.delete(id)); }
}
