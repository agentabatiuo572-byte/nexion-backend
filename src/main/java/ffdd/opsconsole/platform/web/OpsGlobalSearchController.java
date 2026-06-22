package ffdd.opsconsole.platform.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.platform.application.OpsGlobalSearchService;
import ffdd.opsconsole.platform.dto.AdminGlobalSearchResult;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/platform/search")
@RequiredArgsConstructor
public class OpsGlobalSearchController {
    private final OpsGlobalSearchService searchService;

    @GetMapping
    public ApiResult<List<AdminGlobalSearchResult>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) Integer limit) {
        return searchService.search(keyword, limit);
    }
}
