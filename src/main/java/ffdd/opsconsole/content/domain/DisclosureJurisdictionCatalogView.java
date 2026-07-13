package ffdd.opsconsole.content.domain;

public record DisclosureJurisdictionCatalogView(
        String code,
        String name,
        String status,
        long revision,
        long referencedVersionCount,
        boolean hasActiveMapping,
        String lastOperator,
        String updatedAt) {
}
