package ffdd.opsconsole.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record GenesisNodeView(
        Long id,
        Long userId,
        String userNo,
        String ownerCode,
        String holdingNo,
        String seriesCode,
        BigDecimal acquiredPriceUsdt,
        String sourceLabel,
        String status,
        String statusLabel,
        String statusTone,
        LocalDateTime acquiredAt,
        LocalDateTime updatedAt) {
}
