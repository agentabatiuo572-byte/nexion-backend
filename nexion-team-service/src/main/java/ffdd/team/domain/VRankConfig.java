package ffdd.team.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import ffdd.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nx_v_rank_config")
public class VRankConfig extends BaseEntity {
    private String rankCode;
    private String titleEn;
    private String titleCn;
    private BigDecimal selfBuyUsd;
    private Integer directRefs;
    private BigDecimal teamVolumeUsd;
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
