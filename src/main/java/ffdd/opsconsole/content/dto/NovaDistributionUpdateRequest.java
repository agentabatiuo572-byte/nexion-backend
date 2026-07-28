package ffdd.opsconsole.content.dto;

import java.util.List;

public record NovaDistributionUpdateRequest(
        List<Item> items,
        List<Item> expectedItems,
        String operator,
        String reason) {
    public NovaDistributionUpdateRequest(List<Item> items, String operator, String reason) {
        this(items, null, operator, reason);
    }

    public record Item(String key, Integer pct) {
    }
}
