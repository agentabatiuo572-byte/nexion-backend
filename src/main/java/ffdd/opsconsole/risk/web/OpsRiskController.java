package ffdd.opsconsole.risk.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/risk")
public class OpsRiskController {
    private final OpsRiskService riskService;

    public OpsRiskController(OpsRiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return riskService.overview();
    }

    @GetMapping("/cases")
    public ApiResult<List<RiskCaseView>> cases(@ModelAttribute RiskCaseQueryRequest request) {
        return riskService.cases(request);
    }

    @GetMapping("/cases/{caseNo}")
    public ApiResult<RiskCaseView> detail(@PathVariable String caseNo) {
        return riskService.detail(caseNo);
    }

    @PostMapping("/cases/{caseNo}/decision")
    public ApiResult<RiskCaseView> decide(
            @PathVariable String caseNo,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskDecisionRequest request) {
        return riskService.decide(caseNo, idempotencyKey, request);
    }

    @PostMapping("/signals")
    public ApiResult<Map<String, Object>> recordSignal(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody RiskSignalRequest request) {
        return riskService.recordSignal(idempotencyKey, request);
    }
}
