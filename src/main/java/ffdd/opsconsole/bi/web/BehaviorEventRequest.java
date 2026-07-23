package ffdd.opsconsole.bi.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BehaviorEventRequest(
        String eventName,
        String sessionId,
        String route,
        Long dwellMs,
        Double xNorm,
        Double yNorm,
        String zone,
        String elementId,
        Long clientTs,
        String deviceType,
        String locale) {
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("L6_UNKNOWN_FIELD:" + fieldName);
    }
}
