package ffdd.mission.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_points_ledger")
public class PointsLedger extends BaseEntity {
    private Long userId;
    private String bizNo;
    private String bizType;
    private Integer points;
    private Integer balanceAfter;
}
