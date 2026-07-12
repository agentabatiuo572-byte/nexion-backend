package ffdd.opsconsole.content.domain;

import java.util.List;

public record I18nMessagePairView(
        String messageKey,
        String namespace,
        String en,
        String zh,
        String vi,
        String status,
        String version,
        List<String> placeholders) {
}
