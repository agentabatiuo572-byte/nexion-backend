package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_level_log")
public class UserLevelLog extends BaseEntity {
    private Long userId;
    private String levelType;
    private String fromCode;
    private String toCode;
    private String reason;
}

