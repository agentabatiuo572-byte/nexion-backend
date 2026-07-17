package ffdd.opsconsole.market.dto;

import java.math.BigDecimal;

public record GenesisSimulationRequest(String side, BigDecimal quantity, BigDecimal unitPrice,
                                       String reason, String operator) {}
