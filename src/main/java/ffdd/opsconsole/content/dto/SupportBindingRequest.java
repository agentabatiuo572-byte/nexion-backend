package ffdd.opsconsole.content.dto;

import java.util.List;

public record SupportBindingRequest(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictId.class) Long targetAgentAdminId,
        List<Customer> customers, String reason) {
    public record Customer(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictId.class) Long id,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictId.class) Long expectedAssignmentId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictId.class) Long expectedVersion) {}

    public static final class StrictId extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class,"Expected an integer ID or version");
            long value=parser.getLongValue();
            if(value<1 || value>9007199254740991L) return context.reportInputMismatch(Long.class,"Expected a positive safe JSON integer");
            return value;
        }
    }
}
