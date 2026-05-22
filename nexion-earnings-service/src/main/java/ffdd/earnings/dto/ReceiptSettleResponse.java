package ffdd.earnings.dto;

import ffdd.earnings.domain.EarningEvent;
import ffdd.earnings.domain.EarningSummary;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReceiptSettleResponse {
    private List<EarningEvent> events;
    private EarningSummary summary;
}
