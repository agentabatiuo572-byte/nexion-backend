package ffdd.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class HelpArticleUpdateRequest {
    @Size(max = 128)
    private String title;

    @Size(max = 65535)
    private String content;

    @Size(max = 32)
    private String category;

    @Size(max = 32)
    private String level;

    @Size(max = 32)
    private String format;

    @Min(0)
    @Max(1000000)
    private Integer durationMin;

    @Min(0)
    private BigDecimal rewardNex;

    @Min(0)
    @Max(100)
    private Integer progressPct;

    @Min(0)
    @Max(1)
    private Integer featured;

    @Size(max = 16)
    private String emoji;

    @Size(max = 32)
    private String tint;

    @Min(0)
    @Max(1000000)
    private Integer sortOrder;

    @Min(0)
    @Max(1)
    private Integer status;
}
