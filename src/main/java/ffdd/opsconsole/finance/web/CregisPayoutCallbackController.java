package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.application.WithdrawalPayoutCallbackService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/withdrawals/cregis/callbacks")
@RequiredArgsConstructor
public class CregisPayoutCallbackController {
    private final WithdrawalPayoutCallbackService service;

    @PostMapping("/payout")
    public ApiResult<Map<String, Object>> payout(@RequestBody(required = false) Map<String, Object> callback) {
        return service.receive(callback);
    }
}
