package ffdd.compute.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskFailRequest {
    @Size(max = 128)
    private String clientName;

    @Size(max = 255)
    private String reason;
}
