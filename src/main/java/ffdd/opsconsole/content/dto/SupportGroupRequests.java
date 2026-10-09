package ffdd.opsconsole.content.dto;

/** Actor identity always comes from the authenticated session. */
public final class SupportGroupRequests {
    private SupportGroupRequests() {}
    public record Create(String name, Long supervisorAdminId, String reason) {}
    public record Rename(String name,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedVersion, String reason) {}
    public record Status(String status,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedVersion, String reason) {}
    public record Owner(Long supervisorAdminId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedVersion, String reason) {}
    public record Move(Long targetGroupId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedMemberVersion,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long sourceGroupVersion,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long targetGroupVersion, String reason) {}
    public record Qualification(String qualificationKind, String state,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedQualificationVersion,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedAccountVersion, String reason) {}
    public record Route(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long targetGroupId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedRouteVersion,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long sourceGroupVersion,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long targetGroupVersion,String reason) {}
    public static final class StrictVersion extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class,"Expected an integer version");
            long value=parser.getLongValue();
            if(value<0 || value>9007199254740991L)return context.reportInputMismatch(Long.class,"Expected a nonnegative safe JSON integer");
            return value;
        }
    }
}
