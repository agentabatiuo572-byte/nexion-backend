package ffdd.team.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TeamCommissionUnlockResult {
    private int scanned;
    private int posted;
    private int skipped;
    private int failed;
    private int walletPosts;
    private List<Long> commissionIds = new ArrayList<>();
}
