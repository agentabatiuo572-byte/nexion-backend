package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

/** Structured publication of the Rank "How it works" policy. */
@Service
@RequiredArgsConstructor
public class PublishedRankHowPolicyService {
    static final String CONFIG_KEY = "team.rank_how.published";
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,95}");
    private final PlatformConfigFacade config;
    private final Environment environment;
    private final AuditLogService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiResult<Map<String, Object>> publicPolicy(String requestedLocale) {
        try {
            Map<String, Object> document = read();
            if (!"PUBLISHED".equals(document.get("status"))) return unavailable();
            String version = text(document.get("version"));
            Map<String, Object> locales = map(document.get("locales"));
            String locale = resolveLocale(locales, requestedLocale);
            Map<String, Object> policy = map(locales == null ? null : locales.get(locale));
            if (version == null || policy == null || !validPolicy(policy)) return unavailable();
            Map<String, Object> out = new LinkedHashMap<>(policy);
            out.put("version", version);
            out.put("locale", locale);
            out.put("status", "PUBLISHED");
            out.put("source", "server");
            String sourceEnvironment = sourceEnvironment();
            String runId = "";
            if ("SANDBOX".equals(sourceEnvironment)) {
                runId = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
                if (!RUN_ID.matcher(runId).matches()) return unavailable();
            }
            out.put("sourceEnvironment", sourceEnvironment);
            out.put("runId", runId);
            return ApiResult.ok(out);
        } catch (Exception ex) { return unavailable(); }
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
                || locales == null || locales.isEmpty() || locales.size()>10
                || expectedRevision==null || expectedRevision<0
                || reason==null || reason.trim().length()<8 || reason.trim().length()>500)
            return ApiResult.fail(422, "RANK_HOW_POLICY_INVALID");
        for (Map.Entry<String,Object> entry : locales.entrySet()) {
            if(!entry.getKey().matches("[a-z]{2}(?:-[a-z0-9]{2,8})?")) return ApiResult.fail(422,"RANK_HOW_POLICY_INVALID");
            Object raw=entry.getValue();
            Map<String, Object> policy = map(raw);
            if (policy == null || !validPolicy(policy)) return ApiResult.fail(422, "RANK_HOW_POLICY_INVALID");
        }
        try {
            Map<String,Object> before=read(true);
            long currentRevision=revision(before);
            if(currentRevision!=expectedRevision)return ApiResult.fail(409,"RANK_HOW_POLICY_VERSION_CONFLICT");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", version.trim()); document.put("status", status); document.put("locales", locales);
            document.put("revision",currentRevision+1);
            String serialized=mapper.writeValueAsString(document);
            if(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262_144)
                return ApiResult.fail(422,"RANK_HOW_POLICY_TOO_LARGE");
            config.upsertAdminValue(CONFIG_KEY, serialized, "JSON", "published_content", reason.trim());
            audit.recordRequired(AuditLogWriteRequest.builder().action("RANK_HOW_POLICY_CHANGED")
                    .resourceType("PUBLISHED_CONTENT").resourceId(CONFIG_KEY).result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("beforeRevision",currentRevision,"afterRevision",currentRevision+1,
                            "status",status,"version",version.trim(),"reason",reason.trim())).build());
            return ApiResult.ok(document);
        } catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { return ApiResult.fail(422, "RANK_HOW_POLICY_INVALID"); }
    }

    private boolean validPolicy(Map<String, Object> policy) {
        if (!bounded(policy.get("hero"),500) || !(policy.get("sections") instanceof List<?> sections)
                || sections.isEmpty() || sections.size()>100) return false;
        java.util.Set<String> ids=new java.util.HashSet<>();
        for (Object raw : sections) {
            Map<String, Object> section = map(raw);
            if (section == null || !bounded(section.get("id"),64) || !bounded(section.get("title"),256)
                    || !bounded(section.get("body"),10_000) || !ids.add(text(section.get("id")))) return false;
            Object order = section.get("order");
            if (!(order instanceof Number number) || number.doubleValue()!=Math.rint(number.doubleValue())
                    || number.doubleValue()<0 || number.doubleValue()>9_007_199_254_740_991D) return false;
        }
        return true;
    }

    private Map<String, Object> read() throws Exception { return read(false); }
    private Map<String,Object> read(boolean lock)throws Exception{return mapper.readValue((lock?config.activeValueForUpdate(CONFIG_KEY):config.activeValue(CONFIG_KEY)).orElse("{}"),new TypeReference<>(){});}
    private long revision(Map<String,Object> document){Object raw=document.get("revision");if(raw==null)return 0;if(!(raw instanceof Number number)||number.longValue()<0||number.doubleValue()!=number.longValue())throw new IllegalArgumentException("RANK_HOW_REVISION_INVALID");return number.longValue();}
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : null; }
    private String resolveLocale(Map<String, Object> locales, String requested) {
        if (locales == null) return "";
        String normalized = normalize(requested);
        if (map(locales.get(normalized)) != null) return normalized;
        String language = normalized.split("-")[0];
        if (map(locales.get(language)) != null) return language;
        return map(locales.get("en")) == null ? "" : "en";
    }
    private String normalize(String value) { String locale = value == null ? "en" : value.trim().replace('_', '-').toLowerCase(Locale.ROOT); return locale.isBlank() ? "en" : locale; }
    private String text(Object value) { String text = value == null ? null : String.valueOf(value).trim(); return text == null || text.isBlank() ? null : text; }
    private boolean bounded(Object value,int max){String v=text(value);return v!=null&&v.length()<=max;}
    private ApiResult<Map<String, Object>> unavailable() { return ApiResult.fail(503, "RANK_HOW_POLICY_UNAVAILABLE"); }
    private String sourceEnvironment() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (profiles.length == 1 && "test".equals(profiles[0])) return "SANDBOX";
        if (profiles.length == 0 || (profiles.length == 1 && ("dev".equals(profiles[0]) || "prod".equals(profiles[0])))) {
            return "PRODUCTION";
        }
        throw new IllegalStateException("RANK_HOW_RUNTIME_PROFILE_INVALID");
    }
}
