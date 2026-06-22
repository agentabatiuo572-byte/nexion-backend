package ffdd.opsconsole.content.domain;

import java.util.List;

public record I18nMessagePairView(
        String messageKey,
        String en,
        String zh,
        String status,
        String version,
        List<String> placeholders) {
}
