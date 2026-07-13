package ffdd.opsconsole.content.dto;

public record DisclosureJurisdictionCatalogRequest(
        String code,
        String name,
        Long expectedRevision,
        String operator,
        String reason) {
}
