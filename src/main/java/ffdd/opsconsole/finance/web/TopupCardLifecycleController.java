package ffdd.opsconsole.finance.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.TopupCardLifecycleService;
import ffdd.opsconsole.finance.application.TopupGatewaySignatureVerifier;
import ffdd.opsconsole.finance.dto.TopupCardAdmissionRequest;
import ffdd.opsconsole.finance.dto.TopupCardChargebackRequest;
import ffdd.opsconsole.finance.dto.TopupCardFailureRequest;
import ffdd.opsconsole.finance.dto.TopupCardSettlementRequest;
import ffdd.opsconsole.finance.dto.TopupProviderStatementRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/topups")
@RequiredArgsConstructor
public class TopupCardLifecycleController {
    private static final String TIMESTAMP_HEADER = "X-Nexion-Payment-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Nexion-Payment-Signature";

    private final TopupCardLifecycleService service;
    private final TopupGatewaySignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping("/card/admission")
    public ResponseEntity<ApiResult<?>> admit(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return executeSigned(timestamp, signature, rawBody, TopupCardAdmissionRequest.class, service::admit);
    }

    @PostMapping("/card/settlements")
    public ResponseEntity<ApiResult<?>> settle(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return executeSigned(timestamp, signature, rawBody, TopupCardSettlementRequest.class, service::settle);
    }

    @PostMapping("/card/failures")
    public ResponseEntity<ApiResult<?>> fail(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return executeSigned(timestamp, signature, rawBody, TopupCardFailureRequest.class, service::recordFailure);
    }

    @PostMapping("/card/chargebacks")
    public ResponseEntity<ApiResult<?>> chargeback(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return executeSigned(
                timestamp, signature, rawBody, TopupCardChargebackRequest.class,
                service::recordChargeback);
    }

    @PostMapping("/provider-statements")
    public ResponseEntity<ApiResult<?>> ingestProviderStatement(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return executeSigned(
                timestamp, signature, rawBody, TopupProviderStatementRequest.class,
                service::ingestProviderStatement);
    }

    private <T> ResponseEntity<ApiResult<?>> executeSigned(
            String timestamp,
            String signature,
            String rawBody,
            Class<T> requestType,
            SignedOperation<T> operation) {
        TopupGatewaySignatureVerifier.Verification verification =
                signatureVerifier.verify(timestamp, signature, rawBody);
        if (!verification.valid()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(HttpStatus.UNAUTHORIZED.value(), verification.reason()));
        }
        try {
            T request = objectMapper.readValue(rawBody, requestType);
            return ResponseEntity.ok(ApiResult.ok(operation.execute(request)));
        } catch (JsonProcessingException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, "REQUEST_BODY_INVALID"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.unprocessableEntity().body(ApiResult.fail(422, ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.fail(409, ex.getMessage()));
        }
    }

    @FunctionalInterface
    private interface SignedOperation<T> {
        Object execute(T request);
    }
}
