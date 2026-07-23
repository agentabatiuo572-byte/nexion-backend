package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record TopupFeeBufferSnapshot(BigDecimal balanceUsdt, Long version) {
}
