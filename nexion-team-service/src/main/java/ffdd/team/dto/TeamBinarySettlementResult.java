package ffdd.team.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TeamBinarySettlementResult {
    private LocalDate settlementDate;
    private int scanned;
    private int created;
    private int skipped;
    private int failed;
    private List<Long> commissionIds = new ArrayList<>();
}
