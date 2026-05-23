package ffdd.notification.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class EarningGeneratedPayload {
    private String eventNo;
    private Long userId;
    private Long userDeviceId;
    private String receiptNo;
    private String asset;
    private BigDecimal amount;
    private String status;
}
