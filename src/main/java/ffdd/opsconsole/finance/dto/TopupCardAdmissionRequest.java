package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record TopupCardAdmissionRequest(
        String admissionEventId,
        String orderNo,
        Long userId,
        String provider,
        BigDecimal amountUsdt,
        String threeDsStatus,
        String cardBin,
        String clientIp,
        String deviceFingerprint) {
}
