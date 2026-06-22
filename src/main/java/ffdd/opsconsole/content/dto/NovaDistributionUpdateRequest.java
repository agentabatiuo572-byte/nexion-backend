package ffdd.opsconsole.content.dto;

import java.util.List;

public record NovaDistributionUpdateRequest(
        List<Item> items,
        String operator,
        String reason) {
    public record Item(String key, Integer pct) {
    }
}
