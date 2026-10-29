package ffdd.opsconsole.content.application;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Default publications shipped with the code; a missing resource stops startup instead of serving nothing. */
public final class PublishedContentDefaults {
    private PublishedContentDefaults() { }

    public static String read(String resource) {
        try (InputStream stream = PublishedContentDefaults.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("PUBLISHED_DEFAULT_MISSING " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("PUBLISHED_DEFAULT_UNREADABLE " + resource, ex);
        }
    }
}
