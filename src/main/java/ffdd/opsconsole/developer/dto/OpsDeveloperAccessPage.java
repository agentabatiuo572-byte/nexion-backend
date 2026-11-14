package ffdd.opsconsole.developer.dto;

import java.util.List;
import java.util.Map;

/** A9-only page envelope: preserves PageResult's four JSON fields and adds status counts. */
public record OpsDeveloperAccessPage(
        long total,
        long pageNum,
        long pageSize,
        List<Map<String, Object>> records,
        Map<String, Long> statusCounts) {
}
