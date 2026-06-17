package ffdd.opsconsole.market.dto;

import java.util.List;

public record NexMarketCurveUpdateRequest(
        List<NexMarketCurveFrame> frames,
        String reason,
        String operator) {
}
