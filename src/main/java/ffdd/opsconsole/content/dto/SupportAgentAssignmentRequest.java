package ffdd.opsconsole.content.dto;

public record SupportAgentAssignmentRequest(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long userId,
        String operator,
        String reason,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedAssignmentId,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedVersion) {
    public SupportAgentAssignmentRequest(Long userId, String operator, String reason) { this(userId,operator,reason,null,null); }
}
