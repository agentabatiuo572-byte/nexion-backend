package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_achievement")
public class Achievement extends BaseEntity {
    private String achievementCode;
    private String achievementName;
    private String description;
    private String category;
    private String iconKey;
    private String accentColor;
    private String triggerType;
    private Integer triggerValue;
    private Integer rewardPoints;
    private Integer sortOrder;
    private Integer status;
}
