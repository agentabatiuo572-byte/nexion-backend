package ffdd.opsconsole.market.dto;

import java.util.List;

public record ExchangeSwapStatusRequest(
        Boolean enabled, String reason, String operator, List<String> geoBlock, String triggerBasis) {
    public ExchangeSwapStatusRequest(Boolean enabled, String reason, String operator) {
        this(enabled, reason, operator, List.of(), "OTHER");
    }
}
