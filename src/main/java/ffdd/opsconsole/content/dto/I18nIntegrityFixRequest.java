package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.MessageTranslations;
import java.util.Map;

public record I18nIntegrityFixRequest(
        String messageKey,
        String zh,
        String en,
        String vi,
        String operator,
        String reason,
        Map<String, String> translations) {
    public I18nIntegrityFixRequest {
        translations = MessageTranslations.ordered(translations);
    }

    public I18nIntegrityFixRequest(String messageKey, String zh, String en, String vi, String operator, String reason) {
        this(messageKey, zh, en, vi, operator, reason, null);
    }
}
