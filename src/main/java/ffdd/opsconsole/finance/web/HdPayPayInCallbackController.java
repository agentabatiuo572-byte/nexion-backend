package ffdd.opsconsole.finance.web;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.finance.hdpay.HdPayCallbackService;
import ffdd.opsconsole.finance.hdpay.HdPayProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HdPayPayInCallbackController {
    private final HdPayCallbackService service;

    public HdPayPayInCallbackController(HdPayCallbackService service) {
        this.service = service;
    }

    @PostMapping(
            value = HdPayProperties.PAY_IN_CALLBACK_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestBody(required = false) JsonNode body) {
        return service.accept(body);
    }
}
