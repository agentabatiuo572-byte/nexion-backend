package ffdd.opsconsole.content.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** Backward-compatible publication envelope: drafts retain, but never replace, the public revision. */
public final class PublishedContentSnapshot {
    public static final int MAX_STORED_BYTES = 65_535; // nx_config_item.config_value: MySQL TEXT

    private PublishedContentSnapshot() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> published(Map<String, Object> document) {
        if ("PUBLISHED".equals(document.get("status"))) return document;
        if ("DRAFT".equals(document.get("status")) && document.get("published") instanceof Map<?, ?> snapshot)
            return (Map<String, Object>) snapshot;
        return Map.of();
    }

    public static void retainPublished(Map<String, Object> document, Map<String, Object> before) {
        if (!"DRAFT".equals(document.get("status"))) return;
        Map<String, Object> snapshot = published(before);
        if (!"PUBLISHED".equals(snapshot.get("status"))) return;
        Map<String, Object> retained = new LinkedHashMap<>(snapshot);
        retained.remove("published");
        retained.remove("source");
        retained.remove("configKey");
        retained.remove("hasPublishedVersion");
        document.put("published", retained);
    }

    public static Map<String, Object> admin(Map<String, Object> document) {
        Map<String, Object> view = new LinkedHashMap<>(document);
        view.put("hasPublishedVersion", "PUBLISHED".equals(published(document).get("status")));
        view.remove("published");
        return view;
    }
}
