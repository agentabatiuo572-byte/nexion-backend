package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

/** Published, server-owned developer documentation consumed by App remote. */
@Service
@RequiredArgsConstructor
public class PublishedDeveloperDocsService {
    static final String CONFIG_KEY = "developer.docs.published";
    private final PlatformConfigFacade config;
    private final Environment environment;
    private final AuditLogService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiResult<Map<String, Object>> publicDocument(String requestedLocale) {
        try {
            Map<String, Object> document = read();
            if (!"PUBLISHED".equals(document.get("status"))) return unavailable();
            String version = text(document.get("version"));
            Map<String, Object> locales = map(document.get("locales"));
            Map<String, Object> copy = locale(locales, requestedLocale);
            if (version == null || copy == null || !validLocale(copy)) return unavailable();
            Map<String, Object> out = new LinkedHashMap<>(copy);
            out.put("version", version);
            out.put("locale", resolvedLocale(locales, requestedLocale));
            out.put("status", "PUBLISHED");
            out.put("source", "server");
            String sourceEnvironment = sourceEnvironment();
            out.put("sourceEnvironment", sourceEnvironment);
            out.put("runId", "SANDBOX".equals(sourceEnvironment) ? environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim() : "");
            return ApiResult.ok(out);
        } catch (Exception ex) {
            return unavailable();
        }
    }

    public ApiResult<Map<String, Object>> adminView() {
        try {
            Map<String, Object> value = read();
            value.put("source", "server");
            value.put("configKey", CONFIG_KEY);
            return ApiResult.ok(value);
        } catch (Exception ex) {
            return ApiResult.ok(Map.of("status", "UNPUBLISHED", "version", "", "locales", Map.of(), "configKey", CONFIG_KEY));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> update(String version, String status, Map<String, Object> locales,
                                                  Long expectedRevision, String reason) {
        if (version == null || version.isBlank() || version.length() > 64
                || (!"PUBLISHED".equals(status) && !"DRAFT".equals(status))
                || locales == null || locales.isEmpty() || locales.size() > 10
                || expectedRevision == null || expectedRevision < 0
                || reason == null || reason.trim().length() < 8 || reason.trim().length() > 500)
            return ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID");
        for (Map.Entry<String, Object> entry : locales.entrySet()) {
            if (!entry.getKey().matches("[a-z]{2}(?:-[a-z0-9]{2,8})?")) return ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID");
            Map<String, Object> locale = map(entry.getValue());
            if (locale == null || !validLocale(locale)) return ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID");
        }
        try {
            Map<String,Object> before = read(true);
            long currentRevision = revision(before);
            if (currentRevision != expectedRevision) return ApiResult.fail(409, "DEVELOPER_DOCS_VERSION_CONFLICT");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", version.trim());
            document.put("status", status);
            document.put("locales", locales);
            document.put("revision", currentRevision + 1);
            String serialized = mapper.writeValueAsString(document);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262_144)
                return ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_TOO_LARGE");
            config.upsertAdminValue(CONFIG_KEY, serialized, "JSON", "published_content", reason.trim());
            audit.recordRequired(AuditLogWriteRequest.builder().action("DEVELOPER_DOCS_PUBLISHED_CONTENT_CHANGED")
                    .resourceType("PUBLISHED_CONTENT").resourceId(CONFIG_KEY).result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("beforeRevision",currentRevision,"afterRevision",currentRevision+1,
                            "status",status,"version",version.trim(),"reason",reason.trim())).build());
            return ApiResult.ok(document);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            return ApiResult.fail(422, "DEVELOPER_DOCS_CONTENT_INVALID");
        }
    }

    private Map<String, Object> read() throws Exception { return read(false); }

    private Map<String, Object> read(boolean lock) throws Exception {
        String raw = (lock ? config.activeValueForUpdate(CONFIG_KEY) : config.activeValue(CONFIG_KEY)).orElse("{}");
        return mapper.readValue(raw, new TypeReference<>() { });
    }

    private long revision(Map<String,Object> document) {
        Object raw=document.get("revision");
        if(raw==null)return 0;
        if(!(raw instanceof Number number)||number.longValue()<0||number.doubleValue()!=number.longValue())
            throw new IllegalArgumentException("DEVELOPER_DOCS_REVISION_INVALID");
        return number.longValue();
    }

    private boolean validLocale(Map<String, Object> locale) {
        Map<String, Object> example = map(locale.get("example"));
        Object endpointsRaw = locale.get("endpoints");
        Object eventsRaw = locale.get("events");
        if (example == null || text(example.get("request")) == null || text(example.get("response")) == null
                || !bounded(example.get("request"),10_000) || !bounded(example.get("response"),10_000)
                || !(endpointsRaw instanceof List<?> endpoints) || endpoints.isEmpty() || endpoints.size()>100
                || !(eventsRaw instanceof List<?> events) || events.isEmpty() || events.size()>100) return false;
        for (Object endpoint : endpoints) {
            Map<String, Object> item = map(endpoint);
            if (item == null || !bounded(item.get("method"),16) || !bounded(item.get("path"),256)) return false;
        }
        return events.stream().allMatch(event -> bounded(event,128));
    }

    private boolean bounded(Object value,int max){String v=text(value);return v!=null&&v.length()<=max;}

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null;
    }

    private Map<String, Object> locale(Map<String, Object> locales, String requested) {
        if (locales == null) return null;
        String normalized = normalizeLocale(requested);
        Map<String, Object> exact = map(locales.get(normalized));
        if (exact != null) return exact;
        String language = normalized.split("-")[0];
        Map<String, Object> base = map(locales.get(language));
        if (base != null) return base;
        return map(locales.get("en"));
    }

    private String resolvedLocale(Map<String, Object> locales, String requested) {
        String normalized = normalizeLocale(requested);
        if (map(locales.get(normalized)) != null) return normalized;
        String language = normalized.split("-")[0];
        if (map(locales.get(language)) != null) return language;
        return "en";
    }

    private String normalizeLocale(String value) {
        String locale = value == null ? "en" : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return locale.isBlank() ? "en" : locale;
    }

    private String text(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? null : text;
    }

    private ApiResult<Map<String, Object>> unavailable() { return ApiResult.fail(503, "DEVELOPER_DOCS_UNAVAILABLE"); }

    private String sourceEnvironment() {
        return UserAuthEnvironment.resolve(environment)
                .map(value -> value == UserAuthEnvironment.SANDBOX ? "SANDBOX" : "PRODUCTION")
                .orElseThrow(() -> new IllegalStateException("DEVELOPER_DOCS_ENVIRONMENT_UNAVAILABLE"));
    }
}
