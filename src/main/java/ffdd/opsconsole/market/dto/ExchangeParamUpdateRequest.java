package ffdd.opsconsole.market.dto;

/**
 * G2 exchange parameter command.
 *
 * <p>{@code expectedValue} is the server-bound old-value snapshot for A2 approval replay; it is only ever
 * filled by the approval component, never trusted from a browser payload.</p>
 */
public record ExchangeParamUpdateRequest(String value, String reason, String operator, String expectedValue) {
    public ExchangeParamUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, null);
    }
}
