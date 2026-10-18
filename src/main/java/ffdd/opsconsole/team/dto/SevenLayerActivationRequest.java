package ffdd.opsconsole.team.dto;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import java.util.Set;

/** Only the approved intent is client input; the activation instant is always server-owned. */
public record SevenLayerActivationRequest(String action, long expectedSevenLayerRevision, long expectedPolicyVersion) {
    public static SevenLayerActivationRequest from(Map<String, Object> params) {
        if (params == null || !params.keySet().equals(Set.of("action", "expectedSevenLayerRevision", "expectedPolicyVersion"))
                || !(params.get("action") instanceof String action) || !Set.of("ACTIVATE", "PAUSE", "RESUME").contains(action))
            throw invalid();
        return new SevenLayerActivationRequest(action, version(params.get("expectedSevenLayerRevision")),
                version(params.get("expectedPolicyVersion")));
    }
    private static long version(Object value) {
        if (!(value instanceof Number) || !value.toString().matches("[0-9]{1,19}")) throw invalid();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException invalid) { throw invalid(); }
    }
    private static BizException invalid() { return new BizException(422, "SEVEN_LAYER_ACTIVATION_REQUEST_INVALID"); }
}
