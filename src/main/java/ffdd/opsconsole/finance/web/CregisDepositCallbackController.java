package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.finance.cregis.CregisDepositService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/openapi/v1/withdrawals/cregis/callbacks", "/api/cregis/callbacks"})
@RequiredArgsConstructor
public class CregisDepositCallbackController {
    private final CregisDepositService service;

    @PostMapping(value = "/deposit", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receive(@RequestBody(required = false) String raw, HttpServletRequest request) {
        return service.receive(raw, request.getRemoteAddr());
    }
}
