package ffdd.opsconsole.market.dto;

import java.util.List;

public record NexMarketCurveUpdateRequest(
        List<NexMarketCurveFrame> frames,
        String reason,
        String operator,
        List<NexMarketCurveFrame> expectedFrames) {
    public NexMarketCurveUpdateRequest(
            List<NexMarketCurveFrame> frames,
            String reason,
            String operator) {
        this(frames, reason, operator, null);
    }
}
