package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Upgrades only the known development publication, never an operator-edited or disabled document. */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevelopmentCommissionHowInitializer implements ApplicationRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTENT_KEY = "team-commissions-how";
    private final PlatformConfigFacade config;
    private final PublishedHowContentService publications;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        // The exact-entry check and audited revision CAS share this row lock and transaction.
        var existing = config.activeValueForUpdate(PublishedHowContentService.CONFIG_KEY);
        if (existing.isEmpty()) return;
        Map<String, Object> before;
        try { before = JSON.readValue(existing.get(), new TypeReference<>() {}); }
        catch (Exception malformed) { return; }
        var baseline = baseline();
        var contents = map(before.get("contents"));
        Object rawRevision = before.get("revision");
        if (!"PUBLISHED".equals(before.get("status"))
                || !baseline.get("previousVersion").equals(before.get("version"))
                || !"PRODUCTION".equals(before.get("sourceEnvironment"))
                || !"".equals(before.get("runId"))
                || !(rawRevision instanceof Number revision) || revision.longValue() < 0
                || revision.doubleValue() != revision.longValue()
                || !contents.keySet().equals(PublishedHowContentService.CONTENT_KEYS)
                || !baseline.get("previousEntry").equals(contents.get(CONTENT_KEY))) return;
        var updated = new LinkedHashMap<>(contents);
        updated.put(CONTENT_KEY, baseline.get("entry"));
        var result = publications.update((String) baseline.get("version"), "PUBLISHED", updated,
                revision.longValue(), "Upgrade exact development commissions guide baseline; preserve all other published pages");
        if (result.getCode() != 0) throw new IllegalStateException("COMMISSION_HOW_BASELINE_UPGRADE_FAILED");
    }

    private Map<String, Object> baseline() {
        try (var stream = getClass().getResourceAsStream("/policies/commissions-how-2026.08.31.json")) {
            if (stream == null) throw new IllegalStateException("RESOURCE_MISSING");
            return JSON.readValue(stream, new TypeReference<>() {});
        } catch (Exception invalid) { throw new IllegalStateException("COMMISSION_HOW_BASELINE_INVALID", invalid); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
}
