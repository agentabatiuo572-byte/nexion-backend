package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.mapper.BusinessCalendarMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * R-1: the super admin picks one business time zone for the whole platform (D-13). A change takes effect from
 * a future business date (D-15) and settled periods keep their original zone. Choosing a country only pre-fills
 * its zone (D-14). Zones with daylight saving time and China are not offered (Q3).
 */
@Service
@RequiredArgsConstructor
public class BusinessCalendarService {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessCalendarService.class);
    public record ZoneOption(String countryCode, String countryName, String zoneId, String label) { }
    public record VersionView(Long id, String track, String zoneId, String countryCode, String offset, LocalDate effectiveDate,
                              LocalDateTime effectiveStorageAt, String operator, String reason, LocalDateTime createdAt, boolean pending) { }
    public record CalendarView(String currentZoneId, String currentZoneLabel, String currentOffset, boolean unified,
                               Map<String, String> trackZones, VersionView pending, List<VersionView> history,
                               List<ZoneOption> options, LocalDate earliestEffectiveDate) { }
    public record ScheduleRequest(String countryCode, String zoneId, LocalDate effectiveDate, String reason) { }
    public record CancelRequest(Long versionId, String reason) { }

    static final List<ZoneOption> OPTIONS = List.of(
            new ZoneOption("VN", "越南", "Asia/Ho_Chi_Minh", "越南时间"),
            new ZoneOption("TH", "泰国", "Asia/Bangkok", "泰国时间"),
            new ZoneOption("KH", "柬埔寨", "Asia/Phnom_Penh", "柬埔寨时间"),
            new ZoneOption("LA", "老挝", "Asia/Vientiane", "老挝时间"),
            new ZoneOption("MM", "缅甸", "Asia/Yangon", "缅甸时间"),
            new ZoneOption("MY", "马来西亚", "Asia/Kuala_Lumpur", "马来西亚时间"),
            new ZoneOption("SG", "新加坡", "Asia/Singapore", "新加坡时间"),
            new ZoneOption("ID", "印度尼西亚", "Asia/Jakarta", "印尼西部时间"),
            new ZoneOption("ID", "印度尼西亚", "Asia/Makassar", "印尼中部时间"),
            new ZoneOption("ID", "印度尼西亚", "Asia/Jayapura", "印尼东部时间"),
            new ZoneOption("PH", "菲律宾", "Asia/Manila", "菲律宾时间"),
            new ZoneOption("JP", "日本", "Asia/Tokyo", "日本时间"),
            new ZoneOption("KR", "韩国", "Asia/Seoul", "韩国时间"),
            new ZoneOption("IN", "印度", "Asia/Kolkata", "印度时间"),
            new ZoneOption("BD", "孟加拉国", "Asia/Dhaka", "孟加拉时间"),
            new ZoneOption("PK", "巴基斯坦", "Asia/Karachi", "巴基斯坦时间"),
            new ZoneOption("AE", "阿联酋", "Asia/Dubai", "阿联酋时间"),
            new ZoneOption("SA", "沙特阿拉伯", "Asia/Riyadh", "沙特时间"),
            new ZoneOption(null, "协调世界时", "UTC", "协调世界时（UTC）"));
    private static final Map<String, String> LABELS = Map.of("Asia/Shanghai", "北京时间");

    private final BusinessCalendarMapper mapper;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final Clock clock;

    @PostConstruct
    void init() {
        try {
            mapper.ensureTable();
            for (BusinessCalendar.Version seed : BusinessCalendar.seed()) {
                mapper.seed(seed.track(), seed.zone().getId(), "Asia/Shanghai".equals(seed.zone().getId()) ? "CN" : "VN",
                        offsetMinutes(seed.zone()), seed.effectiveDate(), seed.effectiveStorageAt(),
                        "Asia/Shanghai".equals(seed.zone().getId()) ? "历史口径：北京时间" : "增长与团队自 2026-10-19 起按越南时间切分");
            }
            refresh();
        } catch (RuntimeException ex) {
            LOG.warn("Business calendar not loaded; seed calendar stays in effect: {}", ex.getClass().getSimpleName());
        }
    }

    /** Picks up versions written by another instance and the moment a pending version takes effect. */
    @Scheduled(fixedDelayString = "${nexion.business-calendar.refresh-ms:60000}", initialDelayString = "${nexion.business-calendar.refresh-ms:60000}")
    public void refresh() {
        List<BusinessCalendar.Version> loaded = mapper.listActive().stream()
                .map(row -> new BusinessCalendar.Version(row.track(), ZoneId.of(row.zoneId()), row.effectiveDate(), row.effectiveStorageAt()))
                .toList();
        BusinessCalendar.replaceVersions(loaded);
    }

    /** The JVM-wide calendar only picks up a write once it is committed; a rolled-back write never reaches it. */
    private void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { refresh(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { refresh(); }
        });
    }

    /** D-16: what the App needs to cut business days itself; no operator, reason or pending detail. */
    public record AppTrackVersion(String zoneId, int offsetMinutes, String effectiveAt) { }
    public record AppCalendarView(Map<String, List<AppTrackVersion>> tracks) { }

    public ApiResult<AppCalendarView> appView() {
        Map<String, List<AppTrackVersion>> tracks = new LinkedHashMap<>();
        for (BusinessCalendar.Track track : List.of(BusinessCalendar.legacy(), BusinessCalendar.growthTeam())) {
            tracks.put(track.name(), track.versions().stream()
                    .sorted(java.util.Comparator.comparing(BusinessCalendar.Version::effectiveStorageAt))
                    .map(v -> new AppTrackVersion(v.zone().getId(), offsetMinutes(v.zone()),
                            v.effectiveStorageAt().atZone(BusinessCalendar.STORAGE_ZONE).toInstant().toString()))
                    .toList());
        }
        return ApiResult.ok(new AppCalendarView(tracks));
    }

    public ApiResult<CalendarView> view() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<BusinessCalendarMapper.Row> rows = mapper.listActive();
        ZoneId legacy = BusinessCalendar.legacy().currentZone(clock), growth = BusinessCalendar.growthTeam().currentZone(clock);
        Map<String, String> tracks = new LinkedHashMap<>();
        tracks.put(BusinessCalendar.LEGACY, legacy.getId());
        tracks.put(BusinessCalendar.GROWTH_TEAM, growth.getId());
        VersionView pending = rows.stream().filter(row -> isPending(row, now)).map(row -> toView(row, now)).findFirst().orElse(null);
        List<VersionView> history = rows.stream().map(row -> toView(row, now)).toList();
        return ApiResult.ok(new CalendarView(legacy.getId(), label(legacy), offsetText(legacy), legacy.equals(growth), tracks,
                pending, history, OPTIONS, earliestEffectiveDate()));
    }

    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<CalendarView> schedule(String idempotencyKey, ScheduleRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null || request.zoneId() == null || request.effectiveDate() == null) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_REQUEST_INVALID");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 8 || reason.length() > 200) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_REASON_LENGTH_INVALID");
        ZoneOption option = OPTIONS.stream().filter(o -> o.zoneId().equals(request.zoneId())
                && (request.countryCode() == null || request.countryCode().equals(o.countryCode()))).findFirst().orElse(null);
        if (option == null) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_ZONE_NOT_ALLOWED");
        ZoneId zone = ZoneId.of(option.zoneId());
        if (zone.getRules().nextTransition(clock.instant()) != null) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_ZONE_HAS_DST");
        if (request.effectiveDate().isBefore(earliestEffectiveDate())) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_EFFECTIVE_DATE_TOO_EARLY");
        String hash = hash(option.countryCode(), option.zoneId(), request.effectiveDate().toString(), reason);
        return (ApiResult<CalendarView>) idempotencyService.execute("R1_BUSINESS_CALENDAR", idempotencyKey.trim(), hash, ApiResult.class,
                () -> scheduleOnce(option, zone, request.effectiveDate(), reason, idempotencyKey.trim()));
    }

    private ApiResult<CalendarView> scheduleOnce(ZoneOption option, ZoneId zone, LocalDate date, String reason, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.lockWriters();
        if (mapper.countPendingForUpdate(now) > 0) return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "BUSINESS_CALENDAR_PENDING_EXISTS");
        LinkedHashSet<ZoneId> previous = new LinkedHashSet<>();
        previous.add(BusinessCalendar.legacy().versionOnDate(date).zone());
        previous.add(BusinessCalendar.growthTeam().versionOnDate(date).zone());
        LocalDateTime switchAt = BusinessCalendar.switchStorageAt(date, zone, new ArrayList<>(previous));
        if (!switchAt.isAfter(now)) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_EFFECTIVE_DATE_TOO_EARLY");
        String actor = AdminActorResolver.resolve(null);
        mapper.insertGlobal(zone.getId(), option.countryCode(), offsetMinutes(zone), date, switchAt, actor, reason, now);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A_BUSINESS_TIMEZONE_SCHEDULED").resourceType("BUSINESS_CALENDAR").resourceId(zone.getId())
                .actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("before", BusinessCalendar.legacy().currentZone(clock).getId(), "after", zone.getId(),
                        "effectiveDate", date.toString(), "switchStorageAt", switchAt.toString(), "reason", reason,
                        "idempotencyKey", idempotencyKey)).build());
        refreshAfterCommit();
        return view();
    }

    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<CalendarView> cancel(String idempotencyKey, CancelRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED");
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (request == null || request.versionId() == null) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_REQUEST_INVALID");
        if (reason.length() < 8 || reason.length() > 200) return fail(OpsErrorCode.VALIDATION_FAILED, "BUSINESS_CALENDAR_REASON_LENGTH_INVALID");
        return (ApiResult<CalendarView>) idempotencyService.execute("R1_BUSINESS_CALENDAR_CANCEL", idempotencyKey.trim(),
                hash(String.valueOf(request.versionId()), reason), ApiResult.class, () -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    mapper.lockWriters();
                    if (mapper.cancelPending(request.versionId(), now) != 1) return fail(OpsErrorCode.INVALID_STATE_TRANSITION, "BUSINESS_CALENDAR_NOT_PENDING");
                    String actor = AdminActorResolver.resolve(null);
                    auditLogService.recordRequired(AuditLogWriteRequest.builder()
                            .action("A_BUSINESS_TIMEZONE_CANCELLED").resourceType("BUSINESS_CALENDAR").resourceId(String.valueOf(request.versionId()))
                            .actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("HIGH")
                            .detail(Map.of("reason", reason, "idempotencyKey", idempotencyKey.trim())).build());
                    refreshAfterCommit();
                    return view();
                });
    }

    /** Tomorrow, by the latest current business date across tracks. */
    LocalDate earliestEffectiveDate() {
        LocalDate legacy = BusinessCalendar.legacy().today(clock), growth = BusinessCalendar.growthTeam().today(clock);
        return (legacy.isAfter(growth) ? legacy : growth).plusDays(1);
    }

    private static boolean isPending(BusinessCalendarMapper.Row row, LocalDateTime now) {
        return BusinessCalendar.GLOBAL.equals(row.track()) && row.effectiveStorageAt().isAfter(now);
    }

    private static VersionView toView(BusinessCalendarMapper.Row row, LocalDateTime now) {
        return new VersionView(row.id(), row.track(), row.zoneId(), row.countryCode(), offsetText(ZoneId.of(row.zoneId())), row.effectiveDate(),
                row.effectiveStorageAt(), row.operator(), row.reason(), row.createdAt(), isPending(row, now));
    }

    public static String label(ZoneId zone) {
        return LABELS.getOrDefault(zone.getId(), OPTIONS.stream().filter(o -> o.zoneId().equals(zone.getId())).map(ZoneOption::label).findFirst().orElse(zone.getId()));
    }

    static int offsetMinutes(ZoneId zone) { return zone.getRules().getOffset(Instant.EPOCH.plusSeconds(4_000_000_000L)).getTotalSeconds() / 60; }

    static String offsetText(ZoneId zone) {
        int minutes = offsetMinutes(zone);
        return String.format("UTC%s%d%s", minutes < 0 ? "-" : "+", Math.abs(minutes) / 60, Math.abs(minutes) % 60 == 0 ? "" : ":" + String.format("%02d", Math.abs(minutes) % 60));
    }

    private static <T> ApiResult<T> fail(OpsErrorCode code, String message) { return ApiResult.fail(code.httpStatus(), message); }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
