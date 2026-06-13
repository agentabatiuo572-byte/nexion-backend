package ffdd.system.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HelpArticleResponse {
    private Long id;
    private String articleCode;
    private String title;
    private String content;
    private String category;
    private String level;
    private String format;
    private Integer durationMin;
    private BigDecimal rewardNex;
    private Integer progressPct;
    private Integer featured;
    private String emoji;
    private String tint;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
