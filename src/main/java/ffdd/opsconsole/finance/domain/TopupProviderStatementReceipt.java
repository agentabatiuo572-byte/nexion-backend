package ffdd.opsconsole.finance.domain;

public record TopupProviderStatementReceipt(
        String ingestionEventId,
        String statementNo,
        String provider,
        String providerReference,
        String payloadHash,
        String statementStatus) {
}
