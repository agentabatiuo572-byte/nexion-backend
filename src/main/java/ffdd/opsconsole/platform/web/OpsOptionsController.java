package ffdd.opsconsole.platform.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsOptionsService;
import ffdd.opsconsole.platform.dto.AdminOption;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/options")
@RequiredArgsConstructor
public class OpsOptionsController {
    private final OpsOptionsService optionsService;

    @GetMapping("/{domain}/{name}")
    public ApiResult<List<AdminOption>> options(@PathVariable String domain, @PathVariable String name) {
        return optionsService.options(domain, name);
    }
}
