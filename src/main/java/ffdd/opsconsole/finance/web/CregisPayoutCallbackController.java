package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.WithdrawalPayoutCallbackService;
import ffdd.opsconsole.shared.api.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/openapi/v1/withdrawals/cregis/callbacks", "/api/cregis/callbacks"})
@RequiredArgsConstructor
public class CregisPayoutCallbackController {
    private final WithdrawalPayoutCallbackService service;

    @PostMapping(value="/payout",produces=org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public String payout(@RequestBody(required = false) Map<String, Object> callback, HttpServletRequest request) {
        ApiResult<Map<String,Object>> receipt = service.receive(callback, request.getRemoteAddr());
        return receipt.getCode() == 0 ? "success" : "rejected";
    }
}
