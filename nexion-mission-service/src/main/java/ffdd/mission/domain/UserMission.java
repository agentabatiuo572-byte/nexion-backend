package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_mission")
public class UserMission extends BaseEntity {
    private Long userId;
    private Long missionId;
    private String missionStatus;
    private LocalDateTime completedAt;
}
