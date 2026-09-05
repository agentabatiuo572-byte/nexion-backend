package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent publication: editing a draft never replaces the public policy. */
@Service
@RequiredArgsConstructor
public class PublishedPrivacyPolicyService {
    static final String CONFIG_KEY = "legal.privacy-policy.published";
    // nx_config_item.config_value is MySQL TEXT; count UTF-8 bytes including the retained snapshot.
    private static final int MAX_STORED_BYTES = 65_535;
    private final PlatformConfigFacade config;
    private final Environment environment;
    private final AuditLogService audit;
    private final ObjectMapper json = new ObjectMapper();

    public ApiResult<Map<String, Object>> publicPolicy(String requestedLocale) {
        try {
            Map<String, Object> published = map(read(false).get("published"));
            if (published == null || !"PUBLISHED".equals(published.get("status"))
                    || !sourceEnvironment().equals(published.get("sourceEnvironment"))
                    || !runId().equals(published.get("runId"))) return unavailable();
            Map<String, Object> locales = map(published.get("locales"));
            String wanted = requestedLocale == null ? "en" : requestedLocale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
            String locale = locales.containsKey(wanted) ? wanted : locales.containsKey(wanted.split("-")[0])
                    ? wanted.split("-")[0] : "en";
            Map<String, Object> content = map(locales.get(locale));
            if (!validContent(content) || !bounded(published.get("version"), 64)) return unavailable();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hero", content.get("hero"));
            out.put("sections", ((List<?>) content.get("sections")).stream().map(raw -> {
                Map<String, Object> section = map(raw);
                return Map.of("id", section.get("id"), "title", section.get("title"),
                        "body", section.get("body"), "order", section.get("order"));
            }).toList());
            out.put("version", published.get("version"));
            out.put("locale", locale);
            out.put("status", "PUBLISHED");
            out.put("source", "server");
            out.put("sourceEnvironment", sourceEnvironment());
            out.put("runId", runId());
            return ApiResult.ok(out);
        } catch (Exception exception) { return unavailable(); }
    }

    public ApiResult<Map<String, Object>> adminView() {
        Map<String, Object> document = read(false);
        if (document.isEmpty()) document = new LinkedHashMap<>(Map.of(
                "status", "UNPUBLISHED", "version", "", "revision", 0, "locales", Map.of()));
        // The editor does not need the old published body, only whether it remains visible.
        document.put("hasPublishedVersion", document.get("published") instanceof Map<?, ?>);
        document.remove("published");
        document.put("source", "server");
        document.put("configKey", CONFIG_KEY);
        return ApiResult.ok(document);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(String version, String status, Map<String, Object> locales,
                                                Long expectedRevision, String reason) {
        if (!bounded(version, 64) || !Set.of("DRAFT", "PUBLISHED", "UNPUBLISHED").contains(status == null ? "" : status)
                || locales == null || locales.isEmpty() || locales.size() > 10
                || expectedRevision == null || expectedRevision < 0 || !bounded(reason, 500) || reason.trim().length() < 8)
            return ApiResult.fail(422, "PRIVACY_POLICY_INVALID");
        for (var entry : locales.entrySet()) {
            if (!entry.getKey().matches("[a-z]{2}(?:-[a-z0-9]{2,8})?") || !validContent(map(entry.getValue())))
                return ApiResult.fail(422, "PRIVACY_POLICY_INVALID");
        }
        if ("PUBLISHED".equals(status) && !locales.containsKey("en"))
            return ApiResult.fail(422, "PRIVACY_POLICY_DEFAULT_LOCALE_REQUIRED");
        Map<String, Object> before = read(true);
        long revision = revision(before);
        if (revision != expectedRevision) return ApiResult.fail(409, "PRIVACY_POLICY_VERSION_CONFLICT");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", version.trim());
        document.put("status", status);
        document.put("locales", locales);
        document.put("revision", revision + 1);
        document.put("sourceEnvironment", sourceEnvironment());
        document.put("runId", runId());
        if ("PUBLISHED".equals(status)) {
            Map<String, Object> previous = map(before.get("published"));
            if (previous != null && version.trim().equals(previous.get("version")))
                return ApiResult.fail(409, "PRIVACY_POLICY_NEW_VERSION_REQUIRED");
            document.put("published", new LinkedHashMap<>(document));
        } else if ("DRAFT".equals(status) && before.get("published") instanceof Map<?, ?>) {
            document.put("published", before.get("published"));
        }
        try {
            String serialized = json.writeValueAsString(document);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_STORED_BYTES)
                return ApiResult.fail(422, "PRIVACY_POLICY_TOO_LARGE");
            config.upsertAdminValue(CONFIG_KEY, serialized, "JSON", "published_content", reason.trim());
            audit.recordRequired(AuditLogWriteRequest.builder().action("PRIVACY_POLICY_CHANGED")
                    .resourceType("PUBLISHED_CONTENT").resourceId(CONFIG_KEY).result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("beforeRevision", revision, "afterRevision", revision + 1,
                            "status", status, "version", version.trim(), "reason", reason.trim())).build());
            return adminView();
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new BizException(422, "PRIVACY_POLICY_INVALID"); }
    }

    private Map<String, Object> read(boolean lock) {
        try {
            String value = (lock ? config.activeValueForUpdate(CONFIG_KEY) : config.activeValue(CONFIG_KEY)).orElse("{}");
            return json.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) { throw new BizException(503, "PRIVACY_POLICY_UNAVAILABLE"); }
    }
    private long revision(Map<String, Object> document) {
        Object value = document.getOrDefault("revision", 0);
        if (!(value instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue())
            throw new BizException(503, "PRIVACY_POLICY_UNAVAILABLE");
        return number.longValue();
    }
    private boolean validContent(Map<String, Object> content) {
        if (content == null || !bounded(content.get("hero"), 500)
                || !(content.get("sections") instanceof List<?> sections) || sections.isEmpty() || sections.size() > 100) return false;
        Set<String> ids = new HashSet<>();
        for (Object raw : sections) {
            Map<String, Object> section = map(raw);
            if (section == null || !bounded(section.get("id"), 64) || !ids.add((String) section.get("id"))
                    || !bounded(section.get("title"), 256) || !bounded(section.get("body"), 10_000)
                    || !(section.get("order") instanceof Number order) || !Double.isFinite(order.doubleValue())
                    || order.doubleValue() != order.longValue() || order.longValue() < 0 || order.longValue() > 100_000) return false;
        }
        return true;
    }
    private boolean bounded(Object value, int max) { return value instanceof String text && !text.isBlank() && text.length() <= max; }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null; }
    private String sourceEnvironment() { return UserAuthEnvironment.resolve(environment)
            .map(value -> value == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION")
            .orElseThrow(() -> new BizException(503, "PRIVACY_POLICY_UNAVAILABLE")); }
    private String runId() {
        if (!"SANDBOX".equals(sourceEnvironment())) return "";
        String run = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!run.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) throw new BizException(503, "PRIVACY_POLICY_UNAVAILABLE");
        return run;
    }
    private ApiResult<Map<String, Object>> unavailable() { return ApiResult.fail(503, "PRIVACY_POLICY_UNAVAILABLE"); }
}
