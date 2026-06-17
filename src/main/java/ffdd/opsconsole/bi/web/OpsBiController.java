package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.bi.application.OpsBiService;
import ffdd.opsconsole.bi.domain.BiReportView;
import ffdd.opsconsole.bi.dto.BiReportActionRequest;
import ffdd.opsconsole.bi.dto.BiReportQueryRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/bi")
public class OpsBiController {
    private final OpsBiService biService;

    public OpsBiController(OpsBiService biService) {
        this.biService = biService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return biService.overview();
    }

    @GetMapping("/reports")
    public ApiResult<List<BiReportView>> reports(@ModelAttribute BiReportQueryRequest request) {
        return biService.reports(request);
    }

    @GetMapping("/regulatory/templates")
    public ApiResult<Map<String, Object>> regulatoryTemplates() {
        return biService.regulatoryTemplates();
    }

    @PostMapping("/reports/{reportId}/{action}")
    public ApiResult<Map<String, Object>> reportAction(
            @PathVariable String reportId,
            @PathVariable String action,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody BiReportActionRequest request) {
        return biService.reportAction(reportId, action, idempotencyKey, request);
    }
}
