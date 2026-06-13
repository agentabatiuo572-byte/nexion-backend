package ffdd.system.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_help_article")
public class HelpArticle extends BaseEntity {
    private String articleCode;
    private String title;
    private String content;
    private String category;
    private String level;
    private String format;
    private Integer durationMin;
    private java.math.BigDecimal rewardNex;
    private Integer progressPct;
    private Integer featured;
    private String emoji;
    private String tint;
    private Integer sortOrder;
    private Integer status;
}
