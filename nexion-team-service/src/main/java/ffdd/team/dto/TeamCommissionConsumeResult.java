package ffdd.team.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TeamCommissionConsumeResult {
    private int scanned;
    private int processed;
    private int skipped;
    private int failed;
    private List<String> eventIds = new ArrayList<>();
}
