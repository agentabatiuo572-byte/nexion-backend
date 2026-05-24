package ffdd.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ConfigBatchQueryRequest {
    @NotEmpty
    @Size(max = 100)
    private List<@NotBlank @Size(max = 128) String> configKeys;
}
