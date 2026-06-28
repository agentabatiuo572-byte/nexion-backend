package ffdd.opsconsole.bi.domain;

public record BiReportDownloadFile(
        String fileName,
        String contentType,
        byte[] body) {
}
