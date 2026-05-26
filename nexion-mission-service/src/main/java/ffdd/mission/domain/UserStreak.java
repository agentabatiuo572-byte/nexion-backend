package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_user_streak")
public class UserStreak extends BaseEntity {
    private Long userId;
    private Integer currentStreak;
    private Integer longestStreak;
    private Integer streakSavers;
    private LocalDate lastCheckInDate;
}
