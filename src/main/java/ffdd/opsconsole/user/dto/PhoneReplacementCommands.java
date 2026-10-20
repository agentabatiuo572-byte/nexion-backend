package ffdd.opsconsole.user.dto;

public final class PhoneReplacementCommands {
    private PhoneReplacementCommands() { }
    public record Confirm(String reason,Boolean confirmed) { }
    public record Verify(String reason,Boolean confirmed) { }
    public record Issue(String requestId,
                        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ffdd.opsconsole.shared.api.StrictVersionDeserializer.class) Long expectedBindingVersion,Boolean waiveCount,Boolean waiveInterval,
                        String reason,String evidenceRef,Boolean confirmed) { }
}
