package ffdd.compute.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkerTaskLeaseRequest {
    private Long userId;

    private Long preferredDeviceId;

    @NotBlank
    @Size(max = 64)
    private String taskType;

    @NotBlank
    @Size(max = 128)
    private String clientName;

    @Min(1)
    @Max(20)
    private Integer maxAttempts;

    @Min(1)
    @Max(86400)
    private Long leaseSeconds;
}
