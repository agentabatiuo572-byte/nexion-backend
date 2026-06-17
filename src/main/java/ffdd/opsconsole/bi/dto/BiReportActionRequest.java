package ffdd.opsconsole.bi.dto;

public record BiReportActionRequest(
        String reason,
        String operator,
        Boolean includeSensitive,
        Boolean includeDecrypted) {
}
