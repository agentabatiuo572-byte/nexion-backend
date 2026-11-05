package ffdd.opsconsole.market.dto;

/** One reviewed G3 change; decimal inputs remain strings until the market boundary. */
public record G3MarketPolicyChangeRequest(
        String action,
        Integer dayIndex,
        String field,
        String value,
        String expectedRevision) {
}
