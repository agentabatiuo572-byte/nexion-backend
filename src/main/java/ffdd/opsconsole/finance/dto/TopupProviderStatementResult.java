package ffdd.opsconsole.finance.dto;

public record TopupProviderStatementResult(
        String ingestionEventId,
        String statementNo,
        String status,
        boolean replay) {
}
