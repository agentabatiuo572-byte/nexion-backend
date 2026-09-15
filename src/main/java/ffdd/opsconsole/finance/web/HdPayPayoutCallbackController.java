package ffdd.opsconsole.finance.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.HdPayPayoutTransactions;
import ffdd.opsconsole.finance.hdpay.HdPayPayoutCallbackVerifier;
import ffdd.opsconsole.finance.hdpay.HdPayPayoutProperties;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class HdPayPayoutCallbackController {
    private final HdPayPayoutCallbackVerifier verifier;
    private final HdPayPayoutTransactions transactions;
    private final ObjectMapper json;
    @PostMapping(value=HdPayPayoutProperties.CALLBACK_PATH, consumes="application/json", produces="text/plain")
    public String accept(HttpServletRequest request) throws java.io.IOException {
        byte[] bytes = request.getInputStream().readNBytes(16_385);
        if (bytes.length > 16_384) throw new BizException(413, "BANK_CALLBACK_TOO_LARGE");
        try (JsonParser parser = json.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode body = json.readTree(parser);
            if (parser.nextToken() != null) throw new BizException(422, "BANK_CALLBACK_INVALID");
            return transactions.accept(verifier.verify(body));
        }
    }
}
