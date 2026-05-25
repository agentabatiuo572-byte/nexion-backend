package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class I18nMessageCreateRequest {
    @NotBlank
    @Size(max = 128)
    private String messageKey;

    @NotBlank
    @Size(max = 16)
    private String locale;

    @NotBlank
    @Size(max = 1024)
    private String messageValue;

    @Min(0)
    @Max(1)
    private Integer status;
}
