package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record NovaChannelUpsertRequest(
        String key,
        String name,
        String trigger,
        String tick,
        String cooldown,
        BigDecimal ctr,
        Boolean enabled,
        String operator,
        String reason) {
}
