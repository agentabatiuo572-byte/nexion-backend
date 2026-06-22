package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record NovaChannelView(
        String key,
        String name,
        String trigger,
        String tick,
        String cooldown,
        String phaseKeyed,
        BigDecimal ctr,
        boolean enabled) {
}
