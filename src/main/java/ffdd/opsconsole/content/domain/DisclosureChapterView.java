package ffdd.opsconsole.content.domain;

import java.util.Map;

public record DisclosureChapterView(
        String jurisdiction,
        String version,
        String no,
        String zh,
        String vi,
        String en,
        String zhBody,
        String viBody,
        String enBody,
        Map<String, String> titleTranslations, Map<String, String> bodyTranslations,
        String title, String body) {
    public DisclosureChapterView forLanguage(String code) {
        String selectedTitle = switch (code) {
            case "zh" -> zh;
            case "vi" -> vi;
            default -> titleTranslations.getOrDefault(code, en);
        };
        String selectedBody = switch (code) {
            case "zh" -> zhBody;
            case "vi" -> viBody;
            default -> bodyTranslations.getOrDefault(code, enBody);
        };
        return new DisclosureChapterView(jurisdiction, version, no, zh, vi, en, zhBody, viBody, enBody,
                titleTranslations, bodyTranslations, selectedTitle, selectedBody);
    }
    public DisclosureChapterView(
        String jurisdiction,
        String version,
        String no,
        String zh,
        String vi,
        String en,
        String zhBody,
        String viBody,
        String enBody) {
        this(jurisdiction, version, no, zh, vi, en, zhBody, viBody, enBody, Map.of(), Map.of(), null, null);
    }
}
