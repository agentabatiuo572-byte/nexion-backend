package ffdd.team.dto;

import java.math.BigDecimal;

public class VRankConfigUpdateRequest {
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
    private Integer status;

    public String getTitleEn() { return titleEn; }
    public void setTitleEn(String titleEn) { this.titleEn = titleEn; }
    public String getTitleCn() { return titleCn; }
    public void setTitleCn(String titleCn) { this.titleCn = titleCn; }
    public BigDecimal getSelfBuyUsd() { return selfBuyUsd; }
    public void setSelfBuyUsd(BigDecimal selfBuyUsd) { this.selfBuyUsd = selfBuyUsd; }
    public Integer getDirectRefs() { return directRefs; }
    public void setDirectRefs(Integer directRefs) { this.directRefs = directRefs; }
    public BigDecimal getTeamVolumeUsd() { return teamVolumeUsd; }
    public void setTeamVolumeUsd(BigDecimal teamVolumeUsd) { this.teamVolumeUsd = teamVolumeUsd; }
    public String getRequiredDownlineRank() { return requiredDownlineRank; }
    public void setRequiredDownlineRank(String requiredDownlineRank) { this.requiredDownlineRank = requiredDownlineRank; }
    public Integer getRequiredDownlineCount() { return requiredDownlineCount; }
    public void setRequiredDownlineCount(Integer requiredDownlineCount) { this.requiredDownlineCount = requiredDownlineCount; }
    public String getDownlineRequirement() { return downlineRequirement; }
    public void setDownlineRequirement(String downlineRequirement) { this.downlineRequirement = downlineRequirement; }
    public String getUnilevelDepth() { return unilevelDepth; }
    public void setUnilevelDepth(String unilevelDepth) { this.unilevelDepth = unilevelDepth; }
    public BigDecimal getPeerBonusRate() { return peerBonusRate; }
    public void setPeerBonusRate(BigDecimal peerBonusRate) { this.peerBonusRate = peerBonusRate; }
    public Integer getLeadershipVotes() { return leadershipVotes; }
    public void setLeadershipVotes(Integer leadershipVotes) { this.leadershipVotes = leadershipVotes; }
    public String getPhysicalReward() { return physicalReward; }
    public void setPhysicalReward(String physicalReward) { this.physicalReward = physicalReward; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
