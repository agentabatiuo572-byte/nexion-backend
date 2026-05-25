package ffdd.earnings.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EarningMilestoneRewardResult {
    private int scanned;
    private int rewarded;
    private int skipped;
    private List<String> rewardedMilestones = new ArrayList<>();
    private List<String> eventNos = new ArrayList<>();
}
