package ffdd.opsconsole.content.push;

import ffdd.opsconsole.content.push.mapper.EarningPushMapper;
import ffdd.opsconsole.content.push.mapper.EarningPushMapper.*;
import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.StorageTime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * B79-BE-04: four native-only earning reminders generated from committed earning rows and
 * the existing receipt-first-write hook. Station rows and native delivery state stay separate.
 */
@Service
@ConditionalOnProperty(name = "nexion.business-notifications.enabled", havingValue = "true")
public class EarningPushService {
    private static final Logger log = LoggerFactory.getLogger(EarningPushService.class);
    private static final String THREAD = "earning-push";
    private final EarningPushMapper mapper;
    private final PlatformConfigFacade config;

    public EarningPushService(EarningPushMapper mapper, PlatformConfigFacade config) {
        this.mapper = mapper;
        this.config = config;
    }

    /**
     * Called only when the station message for a receipt was really newly inserted. A failure
     * here must not roll back the station projection, so the caller catches it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void onStationFirstWrite(long userId, String receiptNo) {
        if (userId <= 0 || receiptNo == null || receiptNo.isBlank()) return;
        LocalDateTime cutover = mapper.cutover();
        if (cutover == null) return;
        String offset = StorageTime.offset().getId();
        Receipt receipt = mapper.receipt(userId, receiptNo.trim(), offset);
        if (receipt == null || receipt.postedAt() == null) return;
        Preference preference = mapper.preference(userId);
        String mode = preference == null || preference.earningPush() == null ? "DAILY" : preference.earningPush();
        boolean system = preference == null || !Boolean.FALSE.equals(preference.system());
        Installation installation = mapper.installation(userId, idleDays());
        ZoneId zone = installation == null || installation.timeZone() == null ? null : zone(installation.timeZone());
        boolean firstCandidate = isFirstCandidate(userId, receiptNo.trim(), cutover, offset);
        if (firstCandidate) {
            String firstResult = generateSingle(userId, receipt, mode, system, zone, true, mapper.language(userId));
            // Only a really generated first reminder absorbs the receipt's real-time reminder.
            claimSkipped(userId, "REALTIME:" + receipt.receiptNo(), "REALTIME", receipt,
                    firstResult == null ? "MERGED_INTO_FIRST" : firstResult, zone, receipt.postedAt());
        } else {
            generateSingle(userId, receipt, mode, system, zone, false, mapper.language(userId));
        }
    }

    private boolean isFirstCandidate(long userId, String receiptNo, LocalDateTime cutover, String offset) {
        if (mapper.positiveBeforeCutover(userId, cutover, offset) > 0) return false;
        FirstReceipt first = mapper.firstReceipt(userId, offset);
        return first != null && receiptNo.equals(first.receiptNo());
    }

    /** Returns null only when a new notification was generated; otherwise the persisted skip reason. */
    private String generateSingle(long userId, Receipt receipt, String mode, boolean system, ZoneId zone,
                                  boolean first, String language) {
        String key = first ? "FIRST:" + userId : "REALTIME:" + receipt.receiptNo();
        Task existing = mapper.task(key);
        if (existing != null) return skipReason(existing);
        String kind = first ? "FIRST" : "REALTIME";
        LocalDateTime windowStart = floorHalfUtc(receipt.postedAt(), zone);
        LocalDateTime windowEnd = windowStart == null ? receipt.postedAt() : windowStart.plusMinutes(30);
        LocalDateTime expires = expires(receipt.postedAt(), zone);
        if (mapper.insertTask(key, userId, kind, null, receipt.receiptNo(), windowStart, windowEnd,
                zone == null ? null : zone.getId(), 1, receipt.deviceId() == null ? 0 : 1,
                receipt.usdt(), receipt.uvt(), expires) != 1) return "PENDING";
        long id = mapper.task(key).id();
        if (!system || "OFF".equals(mode) || zone == null) {
            mapper.completeTask(id, "SKIPPED", "INELIGIBLE", null, null, null, null);
            return "INELIGIBLE";
        }
        if (!first && !"REALTIME".equals(mode)) {
            mapper.completeTask(id, "SKIPPED", "TIER_NOT_REALTIME", null, null, null, null);
            return "TIER_NOT_REALTIME";
        }
        ZonedDateTime local = receipt.postedAt().toInstant(ZoneOffset.UTC).atZone(zone);
        if (local.getHour() >= 23 || local.getHour() < 8) {
            mapper.completeTask(id, "SKIPPED", "SILENT", null, null, null, null);
            return "SILENT";
        }
        if (expires == null || !Instant.now().isBefore(expires.toInstant(ZoneOffset.UTC))) {
            mapper.completeTask(id, "SKIPPED", "EXPIRED", null, null, null, null);
            return "EXPIRED";
        }
        String amounts = amount(receipt.usdt(), receipt.uvt());
        String title = amounts + (dual(receipt.usdt(), receipt.uvt()) ? ""
                : suffix(first ? "首笔收益到账" : "收益到账",
                         first ? "First earning credited" : "Earnings credited",
                         first ? "Thu nhập đầu tiên đã ghi có" : "Thu nhập đã ghi có", language));
        String body = singleBody(receipt.deviceName(), receipt.taskClass(), language);
        String cta = "/pages/me/receipts?receiptNo=" + receipt.receiptNo();
        return publish(userId, first ? "EARNING_FIRST" : "EARNING_REALTIME", key, title, body, cta, expires)
                ? null : "FAILED";
    }

    private static String skipReason(Task task) {
        return "SENT".equals(task.status()) ? null
                : task.reason() == null || task.reason().isBlank() ? task.status() : task.reason();
    }

    /** One bounded pass; a window is frozen by its unique task key and never extended. */
    @Scheduled(cron = "0 * * * * *")
    @Transactional(rollbackFor = Exception.class)
    public void tick() {
        for (UserSchedule user : mapper.schedules(idleDays())) {
            try {
                if (user.timeZone() == null) continue;
                ZoneId zone = zone(user.timeZone());
                if (zone == null) continue;
                daily(user, zone);
                halfHourly(user, zone);
            } catch (RuntimeException failure) {
                log.warn("Earning push window failed user={} type={}", user.userId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void daily(UserSchedule user, ZoneId zone) {
        ZonedDateTime now = Instant.now().atZone(zone);
        LocalDateTime end = now.toLocalDate().atTime(21, 0);
        if (now.toLocalDateTime().isBefore(end)) return;
        LocalDateTime start = now.toLocalDate().atStartOfDay();
        String key = "DAILY:" + user.userId() + ":" + now.toLocalDate();
        if (mapper.task(key) != null) return;
        if (!"DAILY".equals(user.earningPush())) {
            if (mapper.insertTask(key, user.userId(), "DAILY", "TIER_NOT_DAILY", null,
                    utc(start, zone, now.getOffset()), utc(end, zone, now.getOffset()),
                    zone.getId(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null) == 1) {
                long id = mapper.task(key).id();
                mapper.completeTask(id, "SKIPPED", "TIER_NOT_DAILY", null, null, null, null);
            }
            return;
        }
        if (user.eligible() == null || !user.eligible()) {
            if (mapper.insertTask(key, user.userId(), "DAILY", "SESSION_UNAVAILABLE", null,
                    utc(start, zone, now.getOffset()), utc(end, zone, now.getOffset()),
                    zone.getId(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null) == 1) {
                long id = mapper.task(key).id();
                mapper.completeTask(id, "SKIPPED", "SESSION_UNAVAILABLE", null, null, null, null);
            }
            return;
        }
        generateWindow(user, zone, key, "DAILY", start, end, false, now.getOffset(), now.getOffset());
    }

    private void halfHourly(UserSchedule user, ZoneId zone) {
        ZonedDateTime now = Instant.now().atZone(zone);
        int minute = (now.getMinute() / 30) * 30;
        ZonedDateTime end = now.withMinute(minute).withSecond(0).withNano(0);
        for (int attempt = 0; attempt < 8; attempt++) {
            ZonedDateTime start = end.minusMinutes(30);
            String key = "HALF:" + user.userId() + ":" + start.toInstant().getEpochSecond();
            if (mapper.task(key) == null) {
                LocalDateTime localStart = start.toLocalDateTime();
                LocalDateTime localEnd = end.toLocalDateTime();
                LocalDateTime expiry = expires(end.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(), zone);
                if (expiry != null && !Instant.now().isBefore(expiry.toInstant(ZoneOffset.UTC))) break;
                if (end.toLocalTime().compareTo(LocalTime.of(8, 0)) <= 0 || end.getHour() >= 23) {
                    if (mapper.insertTask(key, user.userId(), "HALF_HOURLY", "SILENT",
                            null, start.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            end.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            zone.getId(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null) == 1) {
                        long id = mapper.task(key).id();
                        mapper.completeTask(id, "SKIPPED", "SILENT", null, null, null, null);
                    }
                } else if (!"HALF_HOURLY".equals(user.earningPush())) {
                    if (mapper.insertTask(key, user.userId(), "HALF_HOURLY", "TIER_NOT_HALF_HOURLY", null,
                            start.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            end.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            zone.getId(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null) == 1) {
                        long id = mapper.task(key).id();
                        mapper.completeTask(id, "SKIPPED", "TIER_NOT_HALF_HOURLY", null, null, null, null);
                    }
                } else if (user.eligible() == null || !user.eligible()) {
                    if (mapper.insertTask(key, user.userId(), "HALF_HOURLY", "SESSION_UNAVAILABLE", null,
                            start.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            end.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime(),
                            zone.getId(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null) == 1) {
                        long id = mapper.task(key).id();
                        mapper.completeTask(id, "SKIPPED", "SESSION_UNAVAILABLE", null, null, null, null);
                    }
                } else {
                    generateWindow(user, zone, key, "HALF_HOURLY", localStart, localEnd, true,
                            start.getOffset(), end.getOffset());
                }
            }
            end = start;
        }
    }

    private void generateWindow(UserSchedule user, ZoneId zone, String key, String kind,
                                LocalDateTime localStart, LocalDateTime localEnd, boolean half,
                                ZoneOffset startOffset, ZoneOffset endOffset) {
        if (mapper.task(key) != null) return;
        LocalDateTime start = utc(localStart, zone, startOffset);
        LocalDateTime end = utc(localEnd, zone, endOffset);
        LocalDateTime cutover = mapper.cutover();
        if (cutover != null && start.isBefore(cutover)) start = cutover;
        if (!start.isBefore(end)) return;
        LocalDateTime expires = expires(end, zone);
        if (mapper.insertTask(key, user.userId(), kind, null, null, start, end, zone.getId(),
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, expires) != 1) return;
        long id = mapper.task(key).id();
        boolean system = user.system() == null || user.system();
        if (!system) { mapper.completeTask(id, "SKIPPED", "INELIGIBLE", null, null, null, null); return; }
        if (expires == null || !Instant.now().isBefore(expires.toInstant(ZoneOffset.UTC))) {
            mapper.completeTask(id, "SKIPPED", "EXPIRED", null, null, null, null); return;
        }
        List<Receipt> receipts = mapper.receiptsBetween(user.userId(), start, end, StorageTime.offset().getId());
        if (receipts == null || receipts.isEmpty()) {
            mapper.completeTask(id, "SKIPPED", "EMPTY", null, null, null, null); return;
        }
        if (half && receipts.size() == 1
                && mapper.sentSingleInWindow(user.userId(), receipts.get(0).receiptNo(), start, end) > 0) {
            mapper.completeTask(id, "SKIPPED", "SINGLE_SUMMARY_SUPPRESSED", null, null, null, null); return;
        }
        BigDecimal usdt = receipts.stream().map(Receipt::usdt).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal uvt = receipts.stream().map(Receipt::uvt).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<Long> devices = new HashSet<>();
        receipts.stream().map(Receipt::deviceId).filter(Objects::nonNull).forEach(devices::add);
        Receipt single = half && receipts.size() == 1 ? receipts.get(0) : null;
        mapper.freezeTask(id, single == null ? null : single.receiptNo(), receipts.size(), devices.size(), usdt, uvt);
        String language = user.language();
        String title, body, cta;
        if ("DAILY".equals(kind)) {
            title = amount(usdt, uvt) + (dual(usdt, uvt) ? ""
                    : suffix("今日收益", "Today's earnings", "Thu nhập hôm nay", language));
            body = dailyBody(receipts.size(), devices.size(), language);
            cta = "/pages/me/notifications?section=notifications&filter=finance";
        } else if (single != null) {
            title = amount(usdt, uvt) + (dual(usdt, uvt) ? ""
                    : suffix("收益到账", "Earnings credited", "Thu nhập đã ghi có", language));
            body = singleBody(single.deviceName(), single.taskClass(), language);
            cta = "/pages/me/receipts?receiptNo=" + single.receiptNo();
        } else {
            title = amount(usdt, uvt) + countSuffix(receipts.size(), language);
            body = deviceBody(devices.size(), language);
            cta = "/pages/me/notifications?section=notifications&filter=finance";
        }
        publish(user.userId(), "DAILY".equals(kind) ? "EARNING_DAILY" : "EARNING_HALF_HOURLY",
                key, title, body, cta, expires);
        // completeTask is updated by publish with the notification id.
    }

    private boolean publish(long userId, String type, String key, String title, String body, String cta,
                            LocalDateTime expires) {
        String biz = "NATIVE1:EARNING:" + hash(key);
        if (mapper.insertNotification(biz, userId, type, title, body, cta) != 1) return false;
        Long notificationId = mapper.notificationId(biz, userId);
        if (notificationId == null) return false;
        mapper.queueDeliveries(notificationId, userId, expires, THREAD, idleDays());
        mapper.completeTask(mapper.task(key).id(), "SENT", null, title, body, cta, notificationId);
        return true;
    }

    private void claimSkipped(long userId, String key, String kind, Receipt receipt, String reason,
                              ZoneId zone, LocalDateTime postedAt) {
        if (mapper.task(key) != null) return;
        LocalDateTime start = floorHalfUtc(postedAt, zone);
        LocalDateTime end = start == null ? postedAt : start.plusMinutes(30);
        if (mapper.insertTask(key, userId, kind, reason, receipt.receiptNo(), start, end,
                zone == null ? null : zone.getId(), 1, receipt.deviceId() == null ? 0 : 1,
                receipt.usdt(), receipt.uvt(), expires(postedAt, zone)) == 1) {
            long id = mapper.task(key).id();
            mapper.completeTask(id, "SKIPPED", reason, null, null, null, null);
        }
    }

    private static LocalDateTime utc(LocalDateTime local, ZoneId zone, ZoneOffset preferred) {
        return ZonedDateTime.ofLocal(local, zone, preferred).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDateTime floorHalfUtc(LocalDateTime postedUtc, ZoneId zone) {
        if (postedUtc == null || zone == null) return postedUtc;
        ZonedDateTime local = postedUtc.toInstant(ZoneOffset.UTC).atZone(zone);
        int minute = (local.getMinute() / 30) * 30;
        ZonedDateTime start = local.withMinute(minute).withSecond(0).withNano(0);
        return start.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDateTime expires(LocalDateTime businessAtUtc, ZoneId zone) {
        if (businessAtUtc == null || zone == null) return null;
        Instant at = businessAtUtc.toInstant(ZoneOffset.UTC);
        ZonedDateTime local = at.atZone(zone);
        ZonedDateTime next23 = local.toLocalDate().atTime(23, 0).atZone(zone);
        if (!local.isBefore(next23)) next23 = next23.plusDays(1);
        Instant plusHour = at.plusSeconds(3600);
        Instant expiry = plusHour.isBefore(next23.toInstant()) ? plusHour : next23.toInstant();
        return LocalDateTime.ofInstant(expiry, ZoneOffset.UTC);
    }

    private int idleDays() {
        try {
            int value = config.activeValue("auth.session.idle_ttl_days").map(Integer::parseInt).orElse(30);
            return value >= 7 && value <= 90 ? value : 30;
        } catch (NumberFormatException invalid) { return 30; }
    }

    private static ZoneId zone(String id) {
        try { return id == null ? null : ZoneId.of(id); } catch (RuntimeException failure) { return null; }
    }


    private static String amount(BigDecimal usdt, BigDecimal uvt) {
        String usdtText = positive(usdt) ? "+" + decimal(usdt) + "\u00A0USDT" : null;
        String uvtText = positive(uvt) ? "+" + decimal(uvt) + "\u00A0UVT" : null;
        if (usdtText != null && uvtText != null) return usdtText + " · " + uvtText;
        if (usdtText != null) return usdtText;
        return uvtText == null ? "" : uvtText;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static boolean dual(BigDecimal usdt, BigDecimal uvt) { return positive(usdt) && positive(uvt); }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private static String suffix(String zh, String en, String vi, String language) {
        String first = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        String text = first.startsWith("zh") ? zh : first.startsWith("vi") ? vi : en;
        return text.isBlank() ? "" : " · " + text;
    }

    private static String singleBody(String device, String taskClass, String language) {
        List<String> parts = new ArrayList<>();
        if (device != null && !device.isBlank()) parts.add(device);
        String canonical = AppTaskAssignmentService.canonicalTaskClassOrNull(taskClass);
        if (canonical != null) parts.add(canonical);
        if (parts.isEmpty()) {
            String first = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
            return first.startsWith("zh") ? "点击查看这笔收益" : first.startsWith("vi")
                    ? "Nhấn để xem thu nhập này" : "Tap to view this earning";
        }
        return String.join(" · ", parts);
    }

    private static String dailyBody(int count, int devices, String language) {
        String first = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("zh")) return "今天已到账 " + count + " 笔 · 来自 " + devices + " 台设备";
        if (first.startsWith("vi")) return "Hôm nay đã nhận " + count + " khoản · từ " + devices + " thiết bị";
        return count + " earnings credited today · from " + devices + " devices";
    }

    private static String deviceBody(int devices, String language) {
        String first = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("zh")) return "来自 " + devices + " 台设备";
        if (first.startsWith("vi")) return "Từ " + devices + " thiết bị";
        return "From " + devices + " devices";
    }

    private static String countSuffix(int count, String language) {
        String first = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (first.startsWith("zh")) return " · " + count + " 笔收益到账";
        if (first.startsWith("vi")) return " · " + count + " khoản thu nhập đã ghi có";
        return " · " + count + " earnings credited";
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
