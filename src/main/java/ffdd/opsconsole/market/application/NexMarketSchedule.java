package ffdd.opsconsole.market.application;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

record NexMarketSchedule(
        String rawValue,
        String displayValue,
        String cronExpression,
        ZoneId zoneId,
        boolean fallback) {
    static final String DEFAULT_DISPLAY = "每日 00:00 UTC 自动推进";
    private static final String DEFAULT_CRON = "0 0 0 * * *";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");
    private static final Pattern DAILY_TIME = Pattern.compile(".*?(\\d{1,2}):(\\d{2})(?:\\s+([A-Za-z][A-Za-z0-9_./+\\-]+))?.*");
    private static final Pattern CRON_VALUE = Pattern.compile("(?i)^cron\\s*[:=]\\s*(.+?)(?:\\s*[|;]\\s*(?:zone\\s*[:=]\\s*)?([^|;\\s]+))?$");

    static NexMarketSchedule defaultSchedule() {
        return new NexMarketSchedule(DEFAULT_DISPLAY, DEFAULT_DISPLAY, DEFAULT_CRON, DEFAULT_ZONE, true);
    }

    static NexMarketSchedule unconfigured() {
        return new NexMarketSchedule("", "", "", DEFAULT_ZONE, false);
    }

    static NexMarketSchedule parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return defaultSchedule();
        }
        String value = raw.trim();
        NexMarketSchedule cronSchedule = parseCron(value);
        if (cronSchedule != null) {
            return cronSchedule;
        }
        Matcher matcher = DAILY_TIME.matcher(value);
        if (!matcher.matches()) {
            return defaultSchedule(value);
        }
        int hour = parseInt(matcher.group(1), -1);
        int minute = parseInt(matcher.group(2), -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return defaultSchedule(value);
        }
        ZoneId zone = parseZone(matcher.group(3));
        if (zone == null) {
            return defaultSchedule(value);
        }
        String cron = "0 " + minute + " " + hour + " * * *";
        String display = String.format(Locale.ROOT, "每日 %02d:%02d %s 自动推进", hour, minute, zone.getId());
        return new NexMarketSchedule(value, display, cron, zone, false);
    }

    private static NexMarketSchedule parseCron(String value) {
        Matcher matcher = CRON_VALUE.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        String cron = matcher.group(1).trim();
        ZoneId zone = parseZone(matcher.group(2));
        if (zone == null || !CronExpression.isValidExpression(cron)) {
            return defaultSchedule(value);
        }
        String display = "cron:" + cron + "|zone=" + zone.getId();
        return new NexMarketSchedule(value, display, cron, zone, false);
    }

    private static ZoneId parseZone(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static NexMarketSchedule defaultSchedule(String rawValue) {
        return new NexMarketSchedule(rawValue, DEFAULT_DISPLAY, DEFAULT_CRON, DEFAULT_ZONE, true);
    }
}
