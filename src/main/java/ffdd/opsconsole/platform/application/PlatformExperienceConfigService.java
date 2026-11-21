package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.domain.PlatformComputeConfigView;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest.AppDownloadRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigUpdateRequest.ShareChannelRequest;
import ffdd.opsconsole.platform.dto.PlatformExperienceConfigView;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Authoritative A3 store for App/H5 install and share experience metadata. */
@ApplicationService
@RequiredArgsConstructor
public class PlatformExperienceConfigService {
    public static final String VERSION_KEY = "platform.experience.version";
    public static final String BASE_URL_KEY = "platform.experience.share.base_url";
    public static final String CHANNELS_KEY = "platform.experience.share.channels";
    public static final String APP_DOWNLOAD_KEY = "platform.experience.app_download";
    private static final String LANGUAGES_KEY = "platform.experience.interface_languages";
    private static final String OPERATING_LANGUAGES_KEY = "platform.experience.operating_languages";
    private static final String INTERFACE_LANGUAGES_INVALID = "PLATFORM_EXPERIENCE_LANGUAGES_INVALID";
    private static final String OPERATING_LANGUAGES_INVALID = "PLATFORM_OPERATING_LANGUAGES_INVALID";
    private static final List<String> OPERATING_LANGUAGE_CODES = List.of("en", "zh", "vi");
    private static final List<String> DEFAULT_OPERATING_LANGUAGES = List.of("en");
    private static final String GROUP = "platform_app_experience";
    private static final Set<String> CHANNEL_KEYS = Set.of(
            "zalo", "telegram", "whatsapp", "messenger", "sms", "x", "copy", "poster", "system");
    private static final Set<String> INTENT_TYPES = Set.of("web", "scheme", "copy", "poster", "system");
    private static final Set<String> SOURCES = Set.of("official", "unavailable");
    private static final PlatformComputeConfigView.AppDownload UNAVAILABLE_DOWNLOAD =
            new PlatformComputeConfigView.AppDownload("", "", "", "", "", Map.of("zh", "", "en", "", "vi", ""), "unavailable");
    private static final Pattern LOCALIZED_WHITESPACE = Pattern.compile("[\\s\\p{Z}\\uFEFF]*");

    private final PlatformConfigFacade config;
    private final ObjectMapper objectMapper;
    private final AdminIdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final OpsGrowthService growthService;

    public ApiResult<PlatformExperienceConfigView> overview() {
        long version = readVersion(false).orElse(0L);
        Optional<PlatformComputeConfigView.ShareConfig> share = readShare(false);
        ApiResult<OpsGrowthService.HomeFeatureFlags> homeFlags = growthService.platformHomeFeatureFlags();
        if (homeFlags.getCode() != 0 || homeFlags.getData() == null) {
            return ApiResult.fail(homeFlags.getCode(), homeFlags.getMessage());
        }
        return ApiResult.ok(new PlatformExperienceConfigView(
                version,
                share.orElseGet(this::unavailableShare),
                share.isPresent(),
                homeFlags.getData().homeNewcomerTasksEnabled(),
                homeFlags.getData().homeWeeklyPromoEnabled(),
                share.map(value -> List.of("nx_config_item:" + BASE_URL_KEY,
                        "nx_config_item:" + CHANNELS_KEY,
                        "nx_config_item:" + APP_DOWNLOAD_KEY)).orElse(List.of()),
                LocalDateTime.now().toString(), readInterfaceLanguages(), readOperatingLanguages()));
    }

    public List<String> interfaceLanguages() {
        return requireLanguages(readInterfaceLanguages(), INTERFACE_LANGUAGES_INVALID);
    }

    public List<String> operatingLanguages() {
        return requireLanguages(readOperatingLanguages(), OPERATING_LANGUAGES_INVALID);
    }

    private List<String> readInterfaceLanguages() {
        return readLanguages(LANGUAGES_KEY, InterfaceLanguages.CODES, InterfaceLanguages.REQUIRED,
                InterfaceLanguages.DEFAULT);
    }

    private List<String> readOperatingLanguages() {
        return readLanguages(OPERATING_LANGUAGES_KEY, OPERATING_LANGUAGE_CODES, DEFAULT_OPERATING_LANGUAGES,
                DEFAULT_OPERATING_LANGUAGES);
    }

    // Absence uses the default. A stored invalid value is exposed as null only by the A3 editor.
    private List<String> readLanguages(String key, List<String> codes, List<String> required,
            List<String> defaults) {
        Optional<String> value = config.activeValue(key);
        if (value.isEmpty()) return defaults;
        try {
            JsonNode root = objectMapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value.get());
            return configuredLanguages(root, codes, required);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private List<String> requireLanguages(List<String> languages, String error) {
        if (languages == null) throw new BizException(503, error);
        return languages;
    }

    private List<String> configuredLanguages(JsonNode value, List<String> codes, List<String> required) {
        if (value == null || !value.isArray()) return null;
        Set<String> selected = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !codes.contains(item.textValue()) || !selected.add(item.textValue())) return null;
        }
        return selected.containsAll(required) ? codes.stream().filter(selected::contains).toList() : null;
    }

    public ApiResult<PlatformComputeConfigView.ShareConfig> publicConfig(boolean officialLinkFragments) {
        Optional<PlatformComputeConfigView.ShareConfig> share = readShare(false);
        // A missing/invalid A3 projection is a normal unavailable state for
        // App/H5. Return an explicit backend-owned unavailable payload so the
        // clients render a closed state; never synthesize a download URL.
        PlatformComputeConfigView.ShareConfig value = share.orElseGet(this::unavailableShare);
        PlatformComputeConfigView.AppDownload download = value.appDownload();
        if ("unavailable".equals(download.source())) {
            return ApiResult.ok(new PlatformComputeConfigView.ShareConfig(value.baseUrl(), value.channels(), UNAVAILABLE_DOWNLOAD));
        }
        // Released clients reject fragments while parsing the entire platform config.
        // Keep their landing page usable until they opt in; never change stored A3 data.
        if (!officialLinkFragments && StringUtils.hasText(download.officialUrl()) && download.officialUrl().contains("#")) {
            value = new PlatformComputeConfigView.ShareConfig(value.baseUrl(), value.channels(),
                    new PlatformComputeConfigView.AppDownload(download.officialUrl().split("#", 2)[0],
                            download.iosUrl(), download.androidUrl(), download.apkUrl(), download.version(),
                            download.releaseNotes(), download.source()));
        }
        return ApiResult.ok(value);
    }

    @Transactional
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<PlatformExperienceConfigView> update(
            String idempotencyKey, PlatformExperienceConfigUpdateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        String validation = validate(request);
        if (validation != null) return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), validation);
        List<String> languages = configuredLanguages(request.interfaceLanguages(), InterfaceLanguages.CODES, InterfaceLanguages.REQUIRED);
        if (request.interfaceLanguages() != null && languages == null) {
            return ApiResult.fail(422, INTERFACE_LANGUAGES_INVALID);
        }
        List<String> operatingLanguages = configuredLanguages(request.operatingLanguages(),
                OPERATING_LANGUAGE_CODES, DEFAULT_OPERATING_LANGUAGES);
        if (request.operatingLanguages() != null && operatingLanguages == null) {
            return ApiResult.fail(422, OPERATING_LANGUAGES_INVALID);
        }
        String hash = requestHash(request);
        ApiResult result = idempotencyService.execute(
                "A3_PLATFORM_EXPERIENCE", idempotencyKey.trim(), hash, ApiResult.class,
                () -> updateOnce(idempotencyKey.trim(), request, languages, operatingLanguages));
        return (ApiResult<PlatformExperienceConfigView>) result;
    }

    private ApiResult<PlatformExperienceConfigView> updateOnce(
            String idempotencyKey, PlatformExperienceConfigUpdateRequest request, List<String> languages,
            List<String> operatingLanguages) {
        long current = readVersion(true).orElse(0L);
        if (request.expectedVersion() == null || request.expectedVersion() != current) {
            return ApiResult.fail(409, "PLATFORM_EXPERIENCE_VERSION_CONFLICT");
        }
        ApiResult<OpsGrowthService.HomeFeatureFlags> homeFlags = growthService.platformHomeFeatureFlags();
        if (homeFlags.getCode() != 0 || homeFlags.getData() == null) {
            return ApiResult.fail(homeFlags.getCode(), homeFlags.getMessage());
        }
        List<String> previousOperatingLanguages = readOperatingLanguages();
        List<String> nextOperatingLanguages = operatingLanguages == null
                ? requireLanguages(previousOperatingLanguages, OPERATING_LANGUAGES_INVALID) : operatingLanguages;
        List<String> previousLanguages = readInterfaceLanguages();
        List<String> nextLanguages = languages == null
                ? requireLanguages(previousLanguages, INTERFACE_LANGUAGES_INVALID) : languages;
        AppDownloadRequest download = request.appDownload();
        Map<String, String> notes = new LinkedHashMap<>();
        if ("official".equals(trim(download.source()))) {
            notes.putAll(download.releaseNotes());
            String chineseNotes = notes.get("zh");
            boolean blankChineseNotes = isLocalizedBlank(chineseNotes);
            if (blankChineseNotes && !nextOperatingLanguages.contains("zh")) {
                notes.put("zh", notes.get("en"));
            } else if (blankChineseNotes || !StringUtils.hasText(chineseNotes)) {
                return ApiResult.fail(422, "PLATFORM_EXPERIENCE_RELEASE_METADATA_REQUIRED");
            }
        } else {
            Map<String, String> supplied = download.releaseNotes() == null ? Map.of() : download.releaseNotes();
            for (String language : OPERATING_LANGUAGE_CODES) {
                notes.put(language, Objects.toString(supplied.get(language), ""));
            }
        }
        PlatformComputeConfigView.ShareConfig next = toShare(request, Map.copyOf(notes));
        Optional<PlatformComputeConfigView.ShareConfig> previousShare = readShare(true);
        config.upsertAdminValue(BASE_URL_KEY, next.baseUrl(), "STRING", GROUP, "A3 App experience base URL");
        config.upsertAdminValue(CHANNELS_KEY, json(next.channels()), "JSON", GROUP, "A3 App share channels");
        config.upsertAdminValue(APP_DOWNLOAD_KEY, json(next.appDownload()), "JSON", GROUP, "A3 App installer metadata");
        if (languages != null) {
            config.upsertAdminValue(LANGUAGES_KEY, json(languages), "JSON", GROUP, "A3 enabled interface languages");
        }
        if (operatingLanguages != null) {
            config.upsertAdminValue(OPERATING_LANGUAGES_KEY, json(operatingLanguages), "JSON", GROUP, "A3 operating region languages");
        }
        config.upsertAdminValue(VERSION_KEY, String.valueOf(current + 1), "NUMBER", GROUP, "A3 App experience CAS version");
        boolean languagesChanged = !nextLanguages.equals(previousLanguages);
        boolean operatingLanguagesChanged = !nextOperatingLanguages.equals(previousOperatingLanguages);
        if ((!languagesChanged && !operatingLanguagesChanged) || previousShare.filter(next::equals).isEmpty()) {
            auditChange(BASE_URL_KEY, Map.of(
                    "before", previousShare.map(this::json).orElse("<unavailable>"), "after", json(next),
                    "expectedVersion", current, "version", current + 1,
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey));
        }
        if (languagesChanged) {
            // Share templates can exceed the audit detail limit. The language change remains readable on its own.
            auditChange(LANGUAGES_KEY, Map.of(
                    "previousInterfaceLanguages", previousLanguages == null ? "<invalid>" : previousLanguages,
                    "interfaceLanguages", nextLanguages,
                    "expectedVersion", current, "version", current + 1,
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey));
        }
        if (operatingLanguagesChanged) {
            auditChange(OPERATING_LANGUAGES_KEY, Map.of(
                    "previousOperatingLanguages", previousOperatingLanguages == null ? "<invalid>" : previousOperatingLanguages,
                    "operatingLanguages", nextOperatingLanguages,
                    "expectedVersion", current, "version", current + 1,
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey));
        }
        return ApiResult.ok(new PlatformExperienceConfigView(
                current + 1, next, true,
                homeFlags.getData().homeNewcomerTasksEnabled(),
                homeFlags.getData().homeWeeklyPromoEnabled(),
                List.of("nx_config_item:" + BASE_URL_KEY,
                        "nx_config_item:" + CHANNELS_KEY,
                        "nx_config_item:" + APP_DOWNLOAD_KEY),
                LocalDateTime.now().toString(), nextLanguages, nextOperatingLanguages));
    }

    private void auditChange(String key, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A3_PLATFORM_EXPERIENCE_CHANGED").resourceType("A3_PLATFORM_EXPERIENCE")
                .resourceId(key).actorType("ADMIN").actorUsername(AdminActorResolver.resolve(null))
                .result("SUCCESS").riskLevel("MEDIUM").detail(detail).build());
    }

    private String validate(PlatformExperienceConfigUpdateRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            return "PLATFORM_EXPERIENCE_EXPECTED_VERSION_REQUIRED";
        }
        String reason = trim(request.reason());
        if (reason == null || reason.length() < 8 || reason.length() > 200) {
            return "A3_REASON_LENGTH_INVALID";
        }
        if (request.channels() == null || request.appDownload() == null) return "PLATFORM_EXPERIENCE_PAYLOAD_REQUIRED";
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().trim();
        if (!baseUrl.isBlank() && !validUrl(baseUrl, false)) return "PLATFORM_EXPERIENCE_BASE_URL_INVALID";
        Set<String> seen = new java.util.HashSet<>();
        for (ShareChannelRequest channel : request.channels()) {
            if (channel == null || !CHANNEL_KEYS.contains(trim(channel.key()))
                    || !INTENT_TYPES.contains(trim(channel.intentType())) || !seen.add(trim(channel.key()))
                    || channel.enabled() == null) {
                return "PLATFORM_EXPERIENCE_CHANNEL_INVALID";
            }
            String urlTemplate = trim(channel.urlTemplate());
            String textTemplate = trim(channel.textTemplate());
            if (!validChannelUrlTemplate(trim(channel.key()), trim(channel.intentType()), urlTemplate)) {
                return "PLATFORM_EXPERIENCE_URL_TEMPLATE_INVALID";
            }
            if (Boolean.TRUE.equals(channel.enabled()) && !Set.of("copy", "poster", "system").contains(trim(channel.intentType()))
                    && textTemplate == null) return "PLATFORM_EXPERIENCE_TEXT_TEMPLATE_REQUIRED";
        }
        AppDownloadRequest download = request.appDownload();
        String source = trim(download.source());
        if (!SOURCES.contains(source)) return "PLATFORM_EXPERIENCE_SOURCE_INVALID";
        String officialUrl = trim(download.officialUrl());
        if (("official".equals(source) || StringUtils.hasText(officialUrl)) && !validUrl(officialUrl, false, true)) {
            return "PLATFORM_EXPERIENCE_OFFICIAL_URL_INVALID";
        }
        if (StringUtils.hasText(download.iosUrl()) && !validUrl(download.iosUrl(), false)) return "PLATFORM_EXPERIENCE_IOS_URL_INVALID";
        if (StringUtils.hasText(download.androidUrl()) && !validUrl(download.androidUrl(), false)) return "PLATFORM_EXPERIENCE_ANDROID_URL_INVALID";
        if (!"unavailable".equals(source)) {
            if (StringUtils.hasText(download.apkUrl()) && !validUrl(download.apkUrl(), false)) return "PLATFORM_EXPERIENCE_APK_URL_INVALID";
            String englishNotes = download.releaseNotes() == null ? null : download.releaseNotes().get("en");
            if (!StringUtils.hasText(download.version()) || isLocalizedBlank(englishNotes) || !StringUtils.hasText(englishNotes)) {
                return "PLATFORM_EXPERIENCE_RELEASE_METADATA_REQUIRED";
            }
        }
        return null;
    }

    private boolean isLocalizedBlank(String value) {
        return value == null || LOCALIZED_WHITESPACE.matcher(value).matches();
    }

    private PlatformComputeConfigView.ShareConfig toShare(PlatformExperienceConfigUpdateRequest request, Map<String, String> notes) {
        List<PlatformComputeConfigView.ShareChannel> channels = request.channels().stream()
                .map(channel -> new PlatformComputeConfigView.ShareChannel(
                        trim(channel.key()), trim(channel.intentType()), trim(channel.textTemplate()), trim(channel.urlTemplate()),
                        trim(channel.androidPackage()), trim(channel.iosScheme()), Boolean.TRUE.equals(channel.enabled())))
                .toList();
        AppDownloadRequest download = request.appDownload();
        boolean available = "official".equals(trim(download.source()));
        return new PlatformComputeConfigView.ShareConfig(
                trim(request.baseUrl()) == null ? "" : trim(request.baseUrl()), channels,
                new PlatformComputeConfigView.AppDownload(
                        download.officialUrl() == null ? "" : trim(download.officialUrl()),
                        download.iosUrl() == null ? "" : trim(download.iosUrl()),
                        download.androidUrl() == null ? "" : trim(download.androidUrl()),
                        available && trim(download.apkUrl()) != null ? trim(download.apkUrl()) : "",
                        download.version() == null ? "" : trim(download.version()),
                        notes,
                        available ? "official" : "unavailable"));
    }

    private Optional<PlatformComputeConfigView.ShareConfig> readShare(boolean lock) {
        Optional<String> base = lock ? config.activeValueForUpdate(BASE_URL_KEY) : config.activeValue(BASE_URL_KEY);
        Optional<String> channels = lock ? config.activeValueForUpdate(CHANNELS_KEY) : config.activeValue(CHANNELS_KEY);
        Optional<String> download = lock ? config.activeValueForUpdate(APP_DOWNLOAD_KEY) : config.activeValue(APP_DOWNLOAD_KEY);
        if (base.isEmpty() || channels.isEmpty() || download.isEmpty()) return Optional.empty();
        try {
            List<PlatformComputeConfigView.ShareChannel> parsedChannels = objectMapper.readValue(
                    channels.get(), objectMapper.getTypeFactory().constructCollectionType(List.class,
                            PlatformComputeConfigView.ShareChannel.class));
            PlatformComputeConfigView.AppDownload parsedDownload = objectMapper.readValue(
                    download.get(), PlatformComputeConfigView.AppDownload.class);
            if (parsedDownload == null || !SOURCES.contains(parsedDownload.source())
                    || (!"unavailable".equals(parsedDownload.source()) && !StringUtils.hasText(parsedDownload.officialUrl()))) {
                return Optional.empty();
            }
            PlatformComputeConfigView.ShareConfig parsed = new PlatformComputeConfigView.ShareConfig(
                    base.get(), parsedChannels == null ? List.of() : parsedChannels, parsedDownload);
            return validStoredShare(parsed) ? Optional.of(parsed) : Optional.empty();
        } catch (JsonProcessingException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Long> readVersion(boolean lock) {
        Optional<String> raw = lock ? config.activeValueForUpdate(VERSION_KEY) : config.activeValue(VERSION_KEY);
        if (raw.isEmpty()) return Optional.empty();
        try {
            long value = Long.parseLong(raw.get().trim());
            return value >= 0 ? Optional.of(value) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private PlatformComputeConfigView.ShareConfig unavailableShare() {
        return new PlatformComputeConfigView.ShareConfig("", List.of(), UNAVAILABLE_DOWNLOAD);
    }

    private boolean validStoredShare(PlatformComputeConfigView.ShareConfig share) {
        if (share == null || (!share.baseUrl().isBlank() && !validUrl(share.baseUrl(), false))) return false;
        if (share.channels() == null) return false;
        Set<String> seen = new java.util.HashSet<>();
        for (PlatformComputeConfigView.ShareChannel channel : share.channels()) {
            if (channel == null || !CHANNEL_KEYS.contains(channel.key()) || !INTENT_TYPES.contains(channel.intentType())
                    || !seen.add(channel.key())) return false;
            if (!validChannelUrlTemplate(channel.key(), channel.intentType(), channel.urlTemplate())) return false;
            if (channel.enabled() && !Set.of("copy", "poster", "system").contains(channel.intentType())
                    && !StringUtils.hasText(channel.textTemplate())) return false;
        }
        PlatformComputeConfigView.AppDownload download = share.appDownload();
        if (download == null || !SOURCES.contains(download.source())) return false;
        if (("official".equals(download.source()) || StringUtils.hasText(download.officialUrl()))
                && !validUrl(download.officialUrl(), false, true)) return false;
        if ((StringUtils.hasText(download.iosUrl()) && !validUrl(download.iosUrl(), false))
                || (StringUtils.hasText(download.androidUrl()) && !validUrl(download.androidUrl(), false))) return false;
        if (!"unavailable".equals(download.source()) && StringUtils.hasText(download.apkUrl())
                && !validUrl(download.apkUrl(), false)) return false;
        if (!"unavailable".equals(download.source())
                && (!StringUtils.hasText(download.version()) || download.releaseNotes() == null
                || !StringUtils.hasText(download.releaseNotes().get("zh"))
                || !StringUtils.hasText(download.releaseNotes().get("en")))) return false;
        if ("unavailable".equals(download.source()) && StringUtils.hasText(download.apkUrl())) return false;
        return true;
    }

    private boolean validUrl(String value, boolean allowHttp) {
        return validUrl(value, allowHttp, false);
    }

    private boolean validUrl(String value, boolean allowHttp, boolean allowFragment) {
        if (!StringUtils.hasText(value) || value.matches(".*[\\s@].*") || (!allowFragment && value.contains("#"))) return false;
        try {
            URI uri = URI.create(value.trim());
            return (allowHttp ? ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    : "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost()) && uri.getUserInfo() == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean hasPlaceholder(String value) {
        return value != null && (value.contains("{link}") || value.contains("{text}"));
    }

    private boolean validChannelUrlTemplate(String channelKey, String intentType, String value) {
        if (!StringUtils.hasText(value)) return !"web".equals(intentType);
        if (!"web".equals(intentType) || !hasPlaceholder(value) || value.matches(".*[\\s#].*")) return false;
        try {
            URI uri = URI.create(value.trim()
                    .replace("{link}", "https%3A%2F%2Fnexgrid.invalid%2Fref%2Fcode")
                    .replace("{text}", "share-text"));
            if ("sms".equals(channelKey)) {
                return "sms".equalsIgnoreCase(uri.getScheme())
                        && uri.getRawSchemeSpecificPart() != null
                        && uri.getRawSchemeSpecificPart().startsWith("?body=")
                        && uri.getFragment() == null;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String requestHash(PlatformExperienceConfigUpdateRequest request) {
        String fields = request.toString();
        // Keep the original fingerprint for old clients replaying an in-flight A3 command.
        if (request.operatingLanguages() == null) {
            fields = fields.substring(0, fields.length() - ", operatingLanguages=null]".length()) + "]";
            if (request.interfaceLanguages() == null) {
                fields = fields.substring(0, fields.length() - ", interfaceLanguages=null]".length()) + "]";
            }
        }
        return Integer.toHexString(fields.hashCode());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("PLATFORM_EXPERIENCE_SERIALIZATION_FAILED", ex);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static <T> ApiResult<T> fail(OpsErrorCode code, String message) {
        return ApiResult.fail(code.httpStatus(), message);
    }
}
