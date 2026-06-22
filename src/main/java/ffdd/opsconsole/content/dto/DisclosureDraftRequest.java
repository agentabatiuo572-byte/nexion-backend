package ffdd.opsconsole.content.dto;

public record DisclosureDraftRequest(
        String version,
        String jurisdiction,
        String languageScope,
        String effectiveDate,
        Boolean requiresReack,
        String zh,
        String en,
        String operator,
        String reason) {
}
