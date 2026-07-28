package ffdd.opsconsole.finance.dto;

/**
 * Server-controlled chain confirmation command.
 *
 * <p>The transaction hash is immutable evidence from the chain executor. The
 * reason and operator are persisted in A2/A4; they are not used to infer a
 * successful payout.</p>
 */
public record WithdrawalConfirmationRequest(
        String chainTxHash,
        String operator,
        String reason) {
}
