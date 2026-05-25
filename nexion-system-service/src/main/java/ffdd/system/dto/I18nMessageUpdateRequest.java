package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class I18nMessageUpdateRequest {
    @Size(max = 1024)
    private String messageValue;

    @Min(0)
    @Max(1)
    private Integer status;
}
