package ffdd.opsconsole.janus.dto;

public record JanusTakeoverAdminRequest(
        Long expectedVersion,
        String targetId,
        Integer targetVersion,
        Long targetCatalogVersion,
        String reason,
        String operator) {
}
