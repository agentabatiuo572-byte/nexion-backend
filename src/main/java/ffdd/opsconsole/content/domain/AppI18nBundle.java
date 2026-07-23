package ffdd.opsconsole.content.domain;

import java.util.Map;

/** Published i18n bundle consumed by the App; draft and archived values never cross this boundary. */
public record AppI18nBundle(
        String namespace,
        String locale,
        Map<String, String> messages,
        boolean serverCanonical) {
}
