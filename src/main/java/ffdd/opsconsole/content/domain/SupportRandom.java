package ffdd.opsconsole.content.domain;

import java.time.LocalDateTime;
import java.util.List;

public final class SupportRandom {
    private SupportRandom() {}
    public record Customer(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ffdd.opsconsole.content.dto.SupportBindingRequest.StrictId.class) Long id,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ffdd.opsconsole.content.dto.SupportBindingRequest.StrictId.class) Long poolVersion) {}
    public record Excluded(Long customerId,String reason) {}
    public record Preview(String id,Long actorId,List<Customer> customers,int count,List<Excluded> excluded,
            Long rulesVersion,LocalDateTime expiresAt) {}
    public record Recipient(Long customerId,String status,Long assignmentId,Long agentAdminId,String outcome) {}
    public record Result(String operationId,String previewId,List<Recipient> customers) {}
}
