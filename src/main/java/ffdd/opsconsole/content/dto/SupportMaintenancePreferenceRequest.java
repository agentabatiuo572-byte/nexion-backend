package ffdd.opsconsole.content.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record SupportMaintenancePreferenceRequest(
        @JsonDeserialize(using=StrictBoolean.class) Boolean enabled, String reason,
        @JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedVersion,
        @JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedAssignmentId) {
    public static final class StrictBoolean extends com.fasterxml.jackson.databind.JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_TRUE)
                    && !parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_FALSE))
                return context.reportInputMismatch(Boolean.class,"Expected a JSON boolean");
            return parser.getBooleanValue();
        }
    }
}
