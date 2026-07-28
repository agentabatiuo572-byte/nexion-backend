package ffdd.opsconsole.bi.domain;

import java.io.IOException;
import java.io.InputStream;

/**
 * Immutable report artifact opened from the authoritative object store.
 */
public record BiReportSnapshot(
        String objectKey,
        String contentType,
        long sizeBytes,
        String sha256,
        InputStream inputStream) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
