package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class HelpArticleUpdateRequest {
    @Size(max = 128)
    private String title;

    @Size(max = 65535)
    private String content;

    @Min(0)
    @Max(1000000)
    private Integer sortOrder;

    @Min(0)
    @Max(1)
    private Integer status;
}
