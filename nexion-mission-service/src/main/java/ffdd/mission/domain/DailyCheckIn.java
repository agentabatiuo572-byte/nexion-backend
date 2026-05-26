package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_daily_check_in")
public class DailyCheckIn extends BaseEntity {
    private Long userId;
    private Long missionId;
    private LocalDate checkInDate;
    private Integer basePoints;
    private BigDecimal rewardMultiplier;
    private Integer bonusPoints;
    private Integer streakBonusPoints;
    private Integer rewardPoints;
}
