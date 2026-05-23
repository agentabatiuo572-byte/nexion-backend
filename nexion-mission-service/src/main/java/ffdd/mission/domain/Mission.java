package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_mission")
public class Mission extends BaseEntity {
    private String missionCode;
    private String missionName;
    private String missionType;
    private Integer rewardPoints;
    private Integer status;
}
