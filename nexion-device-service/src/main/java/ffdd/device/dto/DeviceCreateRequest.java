package ffdd.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceCreateRequest {
    @NotBlank
    private String type;
    private String name;
}

