package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_level_config")
public class UserLevelConfig extends BaseEntity {
    private String levelCode;
    private String levelName;
    private String entryCondition;
    private String coreGoal;
    private Integer sortOrder;
    private Integer status;
}

