package ffdd.earnings.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class EarningTickBatchResult {
    private int requested;
    private int settled;
    private int skipped;
    private int milestoneRewards;
    private List<String> receiptNos = new ArrayList<>();
    private List<String> skippedReasons = new ArrayList<>();
    private List<String> milestoneEventNos = new ArrayList<>();
}
