package ffdd.opsconsole.bi.domain;

import java.io.InputStream;

public record BiReportStreamDownload(
        String fileName,
        String contentType,
        long contentLength,
        InputStream inputStream,
        Runnable onComplete) {
}
