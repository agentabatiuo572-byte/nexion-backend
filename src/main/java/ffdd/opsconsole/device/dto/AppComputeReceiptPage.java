package ffdd.opsconsole.device.dto;

import java.util.List;

public record AppComputeReceiptPage(
        List<AppComputeReceiptSummaryView> items,
        Integer nextOffset,
        String source,
        String sourceEnvironment,
        String runId,
        boolean serverCanonical) {
}
