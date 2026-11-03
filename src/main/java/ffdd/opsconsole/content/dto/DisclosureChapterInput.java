package ffdd.opsconsole.content.dto;

import java.util.Map;

public record DisclosureChapterInput(
        String no,
        String zhTitle,
        String viTitle,
        String enTitle,
        String zhBody,
        String viBody,
        String enBody,
        Map<String, String> titleTranslations, Map<String, String> bodyTranslations) {
    public DisclosureChapterInput(
        String no,
        String zhTitle,
        String viTitle,
        String enTitle,
        String zhBody,
        String viBody,
        String enBody) {
        this(no, zhTitle, viTitle, enTitle, zhBody, viBody, enBody, null, null);
    }
}
