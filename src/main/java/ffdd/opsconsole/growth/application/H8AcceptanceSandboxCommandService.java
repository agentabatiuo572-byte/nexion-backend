package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Dedicated H8 acceptance command facts; never routes sandbox calls through A2 idempotency. */
@Service
@RequiredArgsConstructor
public class H8AcceptanceSandboxCommandService {
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private final ReferralRewardMapper mapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> execute(
            String runId, String idempotencyKey, String requestHash, Supplier<Map<String, Object>> action) {
        ReferralRewardMapper.H8SandboxCommandRow existing = mapper.findSandboxCommand(runId, idempotencyKey);
        if (existing != null) return replay(existing, requestHash);
        if (mapper.insertSandboxCommand(runId, idempotencyKey, requestHash) != 1) {
            ReferralRewardMapper.H8SandboxCommandRow winner = mapper.findSandboxCommand(runId, idempotencyKey);
            if (winner == null) throw conflict("H8_SANDBOX_IDEMPOTENCY_CLAIM_CONFLICT");
            return replay(winner, requestHash);
        }
        Map<String, Object> result = action.get();
        if (mapper.completeSandboxCommand(runId, idempotencyKey, writeJson(result)) != 1) {
            throw conflict("H8_SANDBOX_IDEMPOTENCY_SUCCESS_STATE_LOST");
        }
        return result;
    }

    private Map<String, Object> replay(ReferralRewardMapper.H8SandboxCommandRow row, String requestHash) {
        if (!requestHash.equals(row.requestHash())) {
            throw conflict("H8_SANDBOX_IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        }
        if (STATUS_SUCCEEDED.equals(row.status()) && StringUtils.hasText(row.responseJson())) {
            try {
                return objectMapper.readValue(row.responseJson(), Map.class);
            } catch (JsonProcessingException ex) {
                throw new BizException(500, "H8_SANDBOX_IDEMPOTENCY_RESPONSE_DESERIALIZE_FAILED");
            }
        }
        throw conflict("H8_SANDBOX_IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "H8_SANDBOX_IDEMPOTENCY_RESPONSE_SERIALIZE_FAILED");
        }
    }

    private BizException conflict(String message) {
        return new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), message);
    }
}
