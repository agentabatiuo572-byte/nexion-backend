package ffdd.opsconsole.bi.web;

import ffdd.opsconsole.bi.application.OpsRegulatoryReportService;
import ffdd.opsconsole.bi.dto.RegulatoryReportRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/regulatory")
@RequiredArgsConstructor
public class OpsRegulatoryReportController {
    private final OpsRegulatoryReportService regulatoryReportService;

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('bi_l5_read')")
    public ApiResult<Map<String, Object>> options() {
        return regulatoryReportService.options();
    }

    @PostMapping("/report")
    @PreAuthorize("hasAuthority('bi_l5_regulatory_generate')")
    public ApiResult<Map<String, Object>> report(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RegulatoryReportRequest request) {
        return regulatoryReportService.create(idempotencyKey, request);
    }
}

