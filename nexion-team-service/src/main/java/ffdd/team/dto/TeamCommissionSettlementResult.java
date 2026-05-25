package ffdd.team.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TeamCommissionSettlementResult {
    private String commissionType;
    private String period;
    private BigDecimal sourceVolumeUsdt = BigDecimal.ZERO;
    private BigDecimal poolUsdt = BigDecimal.ZERO;
    private int scanned;
    private int created;
    private int skipped;
    private int failed;
    private List<Long> commissionIds = new ArrayList<>();
}
