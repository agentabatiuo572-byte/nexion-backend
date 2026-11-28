package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.mapper.SkuCreateRequestMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SkuCreateRequests {
    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Map<String, String> TIER_CODES = Map.of("Entry", "e", "Pro", "p", "Flagship", "f", "Share", "s");
    private static final Map<String, String> SERIES_CODES = Map.of(
            "S1", "s1", "PRO", "pro", "PRO_V2", "pv2", "RACK_P1", "rp1", "RACK_P2", "rp2", "CLOUD_SHARE", "cs");

    private final SkuCreateRequestMapper mapper;
    private final ObjectMapper objectMapper;

    public record Draft(String requestId, long makerId, String businessHash, String operationId,
                        String resultSkuId, String resultJson) { }

    record Number(String prefix, int value, String skuId) { }

    static String requestId(String value) {
        if (!StringUtils.hasText(value)) throw new BizException(422, "SKU_CREATE_REQUEST_ID_REQUIRED");
        if (!REQUEST_ID.matcher(value).matches()) throw new BizException(422, "SKU_CREATE_REQUEST_ID_INVALID");
        return value.toLowerCase(Locale.ROOT);
    }

    /** Called inside the proposal or direct create transaction to serialize one draft across both routes. */
    public Draft claim(long actorId, String requestId, String businessHash) {
        mapper.ensureDraft(requestId, actorId, businessHash);
        Draft draft = mapper.lockDraft(requestId);
        if (draft.makerId() != actorId || !draft.businessHash().equals(businessHash)) {
            throw new BizException(409, "SKU_CREATE_REQUEST_CONFLICT");
        }
        return draft;
    }

    public void bindProposal(String requestId, String operationId) {
        if (mapper.bindProposal(requestId, operationId) != 1) {
            throw new BizException(409, "SKU_CREATE_REQUEST_CONFLICT");
        }
    }

    Draft claimForReplay(String requestId, String businessHash, String operationId) {
        Draft draft = mapper.lockDraft(requestId);
        if (draft == null || operationId == null || !operationId.equals(draft.operationId())
                || !businessHash.equals(draft.businessHash())) {
            throw new BizException(409, "SKU_CREATE_REQUEST_CONFLICT");
        }
        return draft;
    }

    Number nextNumber(String tier, String series) {
        String prefix = TIER_CODES.get(tier) + "-" + SERIES_CODES.get(series);
        mapper.ensureSequence(prefix);
        for (int number = mapper.lockSequence(prefix) + 1; number <= 999; number++) {
            String skuId = String.format(Locale.ROOT, "%s-%03d", prefix, number);
            if (!mapper.skuNumberOccupied(skuId)) return new Number(prefix, number, skuId);
        }
        throw new BizException(409, "SKU_NUMBER_SEQUENCE_EXHAUSTED");
    }

    void finish(String requestId, Number number, DeviceSkuView result) {
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("SKU_CREATE_RESULT_SERIALIZE_FAILED", ex);
        }
        mapper.advanceSequence(number.prefix(), number.value());
        if (mapper.finish(requestId, result.skuId(), json) != 1) {
            throw new BizException(409, "SKU_CREATE_REQUEST_CONFLICT");
        }
    }

    DeviceSkuView result(Draft draft) {
        try {
            return objectMapper.readValue(draft.resultJson(), DeviceSkuView.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("SKU_CREATE_RESULT_DESERIALIZE_FAILED", ex);
        }
    }
}
