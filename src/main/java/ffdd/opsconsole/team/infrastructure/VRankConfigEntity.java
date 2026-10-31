package ffdd.opsconsole.team.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.opsconsole.shared.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_v_rank_config")
public class VRankConfigEntity extends BaseEntity {
    private String rankCode;
    private String titleEn;
    private String titleCn;
    private Long selfComputePoints;
    private Integer directRefs;
    private Long teamComputePoints;
    private String requiredDownlineRank;
    private Integer requiredDownlineCount;
    private String downlineRequirement;
    private String unilevelDepth;
    private BigDecimal peerBonusRate;
    private Integer leadershipVotes;
    private String physicalReward;
    private Integer sortOrder;
    private Integer status;
}
