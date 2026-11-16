package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record I18nMessagePairView(
        String messageKey,
        String namespace,
        String en,
        String zh,
        String vi,
        String status,
        String version,
        List<String> placeholders,
        Map<String, String> translations,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime versionCreatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime versionUpdatedAt) {
    public I18nMessagePairView(String messageKey, String namespace, String en, String zh, String vi,
            String status, String version, List<String> placeholders) {
        this(messageKey, namespace, en, zh, vi, status, version, placeholders, Map.of(), null, null);
    }

    public I18nMessagePairView(String messageKey, String namespace, String en, String zh, String vi,
            String status, String version, List<String> placeholders, Map<String, String> translations) {
        this(messageKey, namespace, en, zh, vi, status, version, placeholders, translations, null, null);
    }

    public String localeSet() {
        return "zh-CN,en-US,vi-VN" + translations.keySet().stream().sorted()
                .map(code -> "," + MessageTranslations.LOCALES.get(code)).collect(java.util.stream.Collectors.joining());
    }
}
