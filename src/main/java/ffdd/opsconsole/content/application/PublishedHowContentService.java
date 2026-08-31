package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** One server-owned publication document for all six How-it-works pages. */
@Service
@RequiredArgsConstructor
public class PublishedHowContentService {
    static final String CONFIG_KEY = "how-it-works.published";
    static final Set<String> CONTENT_KEYS = Set.of(
            "genesis-how", "wallet-exchange-how", "wallet-repurchase-how",
            "team-binary-how", "team-commissions-how", "team-unilevel-how");
    private final PlatformConfigFacade config;
    private final Environment environment;
    private final AuditLogService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiResult<Map<String, Object>> publicContent(String contentKey, String requestedLocale) {
        try {
            if (!CONTENT_KEYS.contains(contentKey)) return unavailable();
            Map<String, Object> document = read();
            if (!"PUBLISHED".equals(document.get("status")) || text(document.get("version")) == null) return unavailable();
            String currentEnvironment = sourceEnvironment();
            String publishedEnvironment = text(document.get("sourceEnvironment"));
            String publishedRunId = document.get("runId") == null ? "" : String.valueOf(document.get("runId")).trim();
            String currentRunId = "SANDBOX".equals(currentEnvironment)
                    ? environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim() : "";
            if (!currentEnvironment.equals(publishedEnvironment) || !currentRunId.equals(publishedRunId)) return unavailable();
            Map<String, Object> contents = map(document.get("contents"));
            Map<String, Object> entry = map(contents == null ? null : contents.get(contentKey));
            Map<String, Object> locales = map(entry == null ? null : entry.get("locales"));
            String locale = resolveLocale(locales, requestedLocale);
            Map<String, Object> payload = map(locales == null ? null : locales.get(locale));
            if (payload == null || !validLocale(payload)) return unavailable();
            Map<String, Object> out = new LinkedHashMap<>(payload);
            out.put("contentKey", contentKey);
            out.put("version", text(document.get("version")));
            out.put("locale", locale);
            out.put("status", "PUBLISHED");
            out.put("source", "server");
            out.put("sourceEnvironment", currentEnvironment);
            out.put("runId", currentRunId);
            return ApiResult.ok(out);
        } catch (Exception ex) {
            return unavailable();
        }
    }

    public ApiResult<Map<String, Object>> adminView() {
        try {
            Map<String, Object> value = read();
            if (!hasAdminDocumentShape(value)) value = emptyAdminDocument();
            value.put("source", "server");
            value.put("configKey", CONFIG_KEY);
            return ApiResult.ok(value);
        } catch (Exception ex) {
            Map<String, Object> value = emptyAdminDocument();
            value.put("source", "server");
            value.put("configKey", CONFIG_KEY);
            return ApiResult.ok(value);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(String version, String status, Map<String, Object> contents,
                                                  Long expectedRevision, String reason) {
        if (version == null || version.isBlank() || version.length() > 64
                || (!"PUBLISHED".equals(status) && !"DRAFT".equals(status))
                || contents == null || contents.isEmpty() || contents.size() > CONTENT_KEYS.size()
                || ("PUBLISHED".equals(status) && !contents.keySet().equals(CONTENT_KEYS))
                || expectedRevision == null || expectedRevision < 0
                || reason == null || reason.trim().length() < 8 || reason.trim().length() > 500)
            return invalid();
        for (Map.Entry<String, Object> item : contents.entrySet()) {
            if (!CONTENT_KEYS.contains(item.getKey()) || !validContentEntry(map(item.getValue()))) return invalid();
        }
        try {
            Map<String, Object> before = read(true);
            long currentRevision = revision(before);
            if (currentRevision != expectedRevision) return ApiResult.fail(409, "HOW_CONTENT_VERSION_CONFLICT");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", version.trim());
            document.put("status", status);
            document.put("contents", contents);
            document.put("revision", currentRevision + 1);
            String environmentName = sourceEnvironment();
            String environmentRunId = "SANDBOX".equals(environmentName)
                    ? environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim() : "";
            if ("SANDBOX".equals(environmentName) && environmentRunId.isBlank()) return ApiResult.fail(422, "HOW_CONTENT_RUN_REQUIRED");
            document.put("sourceEnvironment", environmentName);
            document.put("runId", environmentRunId);
            String serialized = mapper.writeValueAsString(document);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > 524_288) return ApiResult.fail(422, "HOW_CONTENT_TOO_LARGE");
            config.upsertAdminValue(CONFIG_KEY, serialized, "JSON", "published_content", reason.trim());
            boolean systemPublication = SecurityContextHolder.getContext().getAuthentication() == null;
            audit.recordRequired(AuditLogWriteRequest.builder().action("HOW_CONTENT_PUBLISHED_CHANGED")
                    .actorType(systemPublication ? "SYSTEM" : "ADMIN")
                    .actorUsername(systemPublication ? "development-baseline" : null)
                    .resourceType("PUBLISHED_CONTENT").resourceId(CONFIG_KEY).result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("beforeRevision", currentRevision, "afterRevision", currentRevision + 1,
                            "status", status, "version", version.trim(), "contentKeys", contents.keySet(), "reason", reason.trim())).build());
            return ApiResult.ok(document);
        } catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { return invalid(); }
    }

    private ApiResult<Map<String, Object>> invalid() { return ApiResult.fail(422, "HOW_CONTENT_INVALID"); }
    private ApiResult<Map<String, Object>> unavailable() { return ApiResult.fail(503, "HOW_CONTENT_UNAVAILABLE"); }
    private Map<String, Object> read() throws Exception { return read(false); }
    private Map<String, Object> read(boolean lock) throws Exception {
        String raw = (lock ? config.activeValueForUpdate(CONFIG_KEY) : config.activeValue(CONFIG_KEY)).orElse("{}");
        return mapper.readValue(raw, new TypeReference<>() { });
    }
    private long revision(Map<String, Object> document) {
        Object raw = document.get("revision");
        if (raw == null) return 0;
        if (!(raw instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue())
            throw new IllegalArgumentException("HOW_CONTENT_REVISION_INVALID");
        return number.longValue();
    }
    private boolean validContentEntry(Map<String, Object> entry) {
        Map<String, Object> locales = map(entry == null ? null : entry.get("locales"));
        if (locales == null || locales.isEmpty() || locales.size() > 10) return false;
        for (Map.Entry<String, Object> item : locales.entrySet()) {
            if (!item.getKey().matches("[a-z]{2}(?:-[a-z0-9]{2,8})?")) return false;
            if (!validLocale(map(item.getValue()))) return false;
        }
        return true;
    }
    private boolean hasAdminDocumentShape(Map<String, Object> value) {
        if (value == null || !value.containsKey("version") || !value.containsKey("status")
                || !Set.of("UNPUBLISHED", "DRAFT", "PUBLISHED").contains(value.get("status"))) return false;
        Map<String, Object> contents = map(value.get("contents"));
        if (contents == null || !contents.keySet().equals(CONTENT_KEYS)) return false;
        try {
            return revision(value) >= 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }
    private Map<String, Object> emptyAdminDocument() {
        Map<String, Object> contents = new LinkedHashMap<>();
        for (String key : CONTENT_KEYS) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("id", "intro");
            block.put("kind", "text");
            block.put("title", key);
            block.put("body", "本页说明由运营后台维护，请补充经审核的教育内容后再发布。");
            contents.put(key, Map.of("locales", Map.of("en", Map.of("blocks", List.of(block)))));
        }
        return new LinkedHashMap<>(Map.of("status", "UNPUBLISHED", "version", "", "revision", 0,
                "contents", contents));
    }
    private boolean validLocale(Map<String, Object> locale) {
        Object rawBlocks = locale == null ? null : locale.get("blocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty() || blocks.size() > 200) return false;
        Set<String> ids = new HashSet<>();
        for (Object raw : blocks) {
            Map<String, Object> block = map(raw);
            String kind = text(block == null ? null : block.get("kind"));
            if (block == null || !Set.of("text", "list", "callout", "ruleRef").contains(kind)
                    || !bounded(block.get("id"), 64) || !bounded(block.get("title"), 256) || !bounded(block.get("body"), 20_000)
                    || !ids.add(text(block.get("id")))) return false;
            if ("list".equals(kind)) {
                if (!(block.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > 50
                        || !items.stream().allMatch(item -> bounded(item, 2_000))) return false;
            }
            if ("ruleRef".equals(kind)) {
                Map<String, Object> ref = map(block.get("ref"));
                if (ref == null || !"canonical".equals(ref.get("source")) || !bounded(ref.get("key"), 160)
                        || !String.valueOf(ref.get("key")).matches("[A-Za-z0-9_.:-]{3,160}") || !bounded(ref.get("version"), 128)
                        || !String.valueOf(block.get("body")).contains("{value}")) return false;
            } else if (block.containsKey("ref")) return false;
        }
        return true;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null; }
    private String resolveLocale(Map<String, Object> locales, String requested) {
        if (locales == null) return "";
        String normalized = normalizeLocale(requested);
        if (map(locales.get(normalized)) != null) return normalized;
        String base = normalized.split("-")[0];
        if (map(locales.get(base)) != null) return base;
        return map(locales.get("en")) == null ? "" : "en";
    }
    private String normalizeLocale(String value) { String locale = value == null ? "en" : value.trim().replace('_', '-').toLowerCase(Locale.ROOT); return locale.isBlank() ? "en" : locale; }
    private String text(Object value) { String text = value == null ? null : String.valueOf(value).trim(); return text == null || text.isBlank() ? null : text; }
    private boolean bounded(Object value, int max) { String text = text(value); return text != null && text.length() <= max; }
    private String sourceEnvironment() {
        return UserAuthEnvironment.resolve(environment)
                .map(value -> value == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION")
                .orElseThrow(() -> new IllegalStateException("HOW_CONTENT_ENVIRONMENT_UNAVAILABLE"));
    }
}
