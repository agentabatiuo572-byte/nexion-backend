package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
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
    private static final String GROUP = "platform_app_experience";
    private static final Set<String> CHANNEL_KEYS = Set.of(
            "zalo", "telegram", "whatsapp", "messenger", "sms", "x", "copy", "poster", "system");
    private static final Set<String> INTENT_TYPES = Set.of("web", "scheme", "copy", "poster", "system");
    private static final Set<String> SOURCES = Set.of("official", "unavailable");

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
                LocalDateTime.now().toString()));
    }

    public ApiResult<PlatformComputeConfigView.ShareConfig> publicConfig() {
        Optional<PlatformComputeConfigView.ShareConfig> share = readShare(false);
        // A missing/invalid A3 projection is a normal unavailable state for
        // App/H5. Return an explicit backend-owned unavailable payload so the
        // clients render a closed state; never synthesize a download URL.
        return ApiResult.ok(share.orElseGet(this::unavailableShare));
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
        String hash = requestHash(request);
        ApiResult result = idempotencyService.execute(
                "A3_PLATFORM_EXPERIENCE", idempotencyKey.trim(), hash, ApiResult.class,
                () -> updateOnce(idempotencyKey.trim(), request));
        return (ApiResult<PlatformExperienceConfigView>) result;
    }

    private ApiResult<PlatformExperienceConfigView> updateOnce(
            String idempotencyKey, PlatformExperienceConfigUpdateRequest request) {
        long current = readVersion(true).orElse(0L);
        if (request.expectedVersion() == null || request.expectedVersion() != current) {
            return ApiResult.fail(409, "PLATFORM_EXPERIENCE_VERSION_CONFLICT");
        }
        ApiResult<OpsGrowthService.HomeFeatureFlags> homeFlags = growthService.platformHomeFeatureFlags();
        if (homeFlags.getCode() != 0 || homeFlags.getData() == null) {
            return ApiResult.fail(homeFlags.getCode(), homeFlags.getMessage());
        }
        PlatformComputeConfigView.ShareConfig next = toShare(request);
        String before = readShare(true).map(this::json).orElse("<unavailable>");
        config.upsertAdminValue(BASE_URL_KEY, next.baseUrl(), "STRING", GROUP, "A3 App experience base URL");
        config.upsertAdminValue(CHANNELS_KEY, json(next.channels()), "JSON", GROUP, "A3 App share channels");
        config.upsertAdminValue(APP_DOWNLOAD_KEY, json(next.appDownload()), "JSON", GROUP, "A3 App installer metadata");
        config.upsertAdminValue(VERSION_KEY, String.valueOf(current + 1), "NUMBER", GROUP, "A3 App experience CAS version");
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A3_PLATFORM_EXPERIENCE_CHANGED")
                .resourceType("A3_PLATFORM_EXPERIENCE")
                .resourceId(BASE_URL_KEY)
                .actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve(null))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(Map.of(
                        "before", before,
                        "after", json(next),
                        "expectedVersion", current,
                        "version", current + 1,
                        "reason", request.reason().trim(),
                        "idempotencyKey", idempotencyKey))
                .build());
        return ApiResult.ok(new PlatformExperienceConfigView(
                current + 1, next, true,
                homeFlags.getData().homeNewcomerTasksEnabled(),
                homeFlags.getData().homeWeeklyPromoEnabled(),
                List.of("nx_config_item:" + BASE_URL_KEY,
                        "nx_config_item:" + CHANNELS_KEY,
                        "nx_config_item:" + APP_DOWNLOAD_KEY),
                LocalDateTime.now().toString()));
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
        if (!"unavailable".equals(source) && !validUrl(officialUrl, false)) {
            return "PLATFORM_EXPERIENCE_OFFICIAL_URL_INVALID";
        }
        if (!"unavailable".equals(source)) {
            if (StringUtils.hasText(download.iosUrl()) && !validUrl(download.iosUrl(), false)) return "PLATFORM_EXPERIENCE_IOS_URL_INVALID";
            if (StringUtils.hasText(download.androidUrl()) && !validUrl(download.androidUrl(), false)) return "PLATFORM_EXPERIENCE_ANDROID_URL_INVALID";
            if (StringUtils.hasText(download.apkUrl()) && !validUrl(download.apkUrl(), false)) return "PLATFORM_EXPERIENCE_APK_URL_INVALID";
        }
        if (!"unavailable".equals(source)) {
            if (!StringUtils.hasText(download.version()) || download.releaseNotes() == null
                    || !StringUtils.hasText(download.releaseNotes().get("zh"))
                    || !StringUtils.hasText(download.releaseNotes().get("en"))) {
                return "PLATFORM_EXPERIENCE_RELEASE_METADATA_REQUIRED";
            }
        }
        return null;
    }

    private PlatformComputeConfigView.ShareConfig toShare(PlatformExperienceConfigUpdateRequest request) {
        List<PlatformComputeConfigView.ShareChannel> channels = request.channels().stream()
                .map(channel -> new PlatformComputeConfigView.ShareChannel(
                        trim(channel.key()), trim(channel.intentType()), trim(channel.textTemplate()), trim(channel.urlTemplate()),
                        trim(channel.androidPackage()), trim(channel.iosScheme()), Boolean.TRUE.equals(channel.enabled())))
                .toList();
        AppDownloadRequest download = request.appDownload();
        Map<String, String> notes = download.releaseNotes() == null ? Map.of() : Map.copyOf(download.releaseNotes());
        boolean available = "official".equals(trim(download.source()));
        return new PlatformComputeConfigView.ShareConfig(
                trim(request.baseUrl()) == null ? "" : trim(request.baseUrl()), channels,
                new PlatformComputeConfigView.AppDownload(
                        available && trim(download.officialUrl()) != null ? trim(download.officialUrl()) : "",
                        available && trim(download.iosUrl()) != null ? trim(download.iosUrl()) : "",
                        available && trim(download.androidUrl()) != null ? trim(download.androidUrl()) : "",
                        available && trim(download.apkUrl()) != null ? trim(download.apkUrl()) : "",
                        available && trim(download.version()) != null ? trim(download.version()) : "",
                        available ? notes : Map.of("zh", "", "en", ""),
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
        return new PlatformComputeConfigView.ShareConfig("", List.of(),
                new PlatformComputeConfigView.AppDownload("", "", "", "", "", Map.of("zh", "", "en", ""), "unavailable"));
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
        if (!"unavailable".equals(download.source())
                && !validUrl(download.officialUrl(), false)) return false;
        if (!"unavailable".equals(download.source())
                && ((StringUtils.hasText(download.iosUrl()) && !validUrl(download.iosUrl(), false))
                || (StringUtils.hasText(download.androidUrl()) && !validUrl(download.androidUrl(), false))
                || (StringUtils.hasText(download.apkUrl()) && !validUrl(download.apkUrl(), false)))) return false;
        if (!"unavailable".equals(download.source())
                && (!StringUtils.hasText(download.version()) || download.releaseNotes() == null
                || !StringUtils.hasText(download.releaseNotes().get("zh"))
                || !StringUtils.hasText(download.releaseNotes().get("en")))) return false;
        if ("unavailable".equals(download.source())
                && (StringUtils.hasText(download.officialUrl()) || StringUtils.hasText(download.iosUrl())
                || StringUtils.hasText(download.androidUrl()) || StringUtils.hasText(download.apkUrl())
                || StringUtils.hasText(download.version()))) return false;
        return true;
    }

    private boolean validUrl(String value, boolean allowHttp) {
        if (!StringUtils.hasText(value) || value.matches(".*[\\s#@].*")) return false;
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
        return Integer.toHexString(String.valueOf(request).hashCode());
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
