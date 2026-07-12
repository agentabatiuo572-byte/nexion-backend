package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_help_article")
public class HelpArticleEntity extends BaseEntity {
    private String articleCode;
    private String title;
    private String content;
    private String category;
    private String level;
    private String format;
    private String surface;
    private Integer durationMin;
    private BigDecimal rewardNex;
    private Integer progressPct;
    private Integer featured;
    private String emoji;
    private String tint;
    private Integer sortOrder;
    private Integer status;
    private String quizJson;
    private Integer quizPassScore;
    private Integer quizRetryLimit;
    private String completionCondition;
    private String rewardEvent;
    private Integer versionNo;
    private Long revision;
}
