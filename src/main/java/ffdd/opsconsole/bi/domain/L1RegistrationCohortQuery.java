package ffdd.opsconsole.bi.domain;

import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A validated registration range and the natural buckets it intersects. */
public record L1RegistrationCohortQuery(
        L1ExportSelection selection, String granularity, Instant asOf,
        LocalDateTime registrationFrom, LocalDateTime registrationToExclusive,
        Instant selectedFrom, Instant selectedToExclusive, List<Bucket> buckets,
        LocalDateTime historyFrom) {
    public static final String CONTRACT_VERSION = "L1_REGISTRATION_COHORT_V1";
    public static final int MAX_POINTS = 60;
    // Match L4's guaranteed A4 fact history, which is measured from the read, not from asOf.
    private static final int MIN_RETENTION_MONTHS = 13;

    public static L1RegistrationCohortQuery parse(String window, String from, String to,
            String cohort, String phase, String locale, String ref, String granularity,
            String asOf, Instant serverNow) {
        if (!"registration_week".equals(granularity) && !"registration_month".equals(granularity)) {
            throw new IllegalArgumentException("L1_GRANULARITY_INVALID");
        }
        Instant cutoff = observationAsOf(asOf, serverNow);
        BusinessCalendar.Track calendar = BusinessCalendar.legacy();
        LocalDate today = calendar.dateAt(cutoff);
        L1ExportSelection selection = L1ExportSelection.parse(window, from, to, cohort, phase, locale, ref,
                calendar.dateAt(serverNow));

        Instant rangeFrom;
        Instant rangeEnd = cutoff;
        if ("custom".equals(selection.window())) {
            LocalDate first = parseDate(selection.from());
            LocalDate last = parseDate(selection.to());
            if (last.isAfter(today)) throw new IllegalArgumentException("L1_REGISTRATION_RANGE_INVALID");
            rangeFrom = calendar.startOf(first);
            if (last.isBefore(today)) rangeEnd = calendar.startOf(last.plusDays(1));
        } else {
            long days = Long.parseLong(selection.window().substring(0, selection.window().length() - 1));
            rangeFrom = cutoff.minus(days, ChronoUnit.DAYS);
        }
        LocalDateTime storageFrom = storageTime(rangeFrom);
        LocalDateTime storageEnd = storageTime(rangeEnd);
        if (storageFrom.getYear() < 1000 || rangeFrom.isAfter(rangeEnd)) {
            throw new IllegalArgumentException("L1_REGISTRATION_RANGE_INVALID");
        }

        Instant effectiveFrom = rangeFrom;
        Instant effectiveEnd = rangeEnd;
        if (!selection.cohort().isEmpty()) {
            LocalDate week = parseWeek(selection.cohort());
            effectiveFrom = later(rangeFrom, calendar.startOf(week));
            effectiveEnd = earlier(rangeEnd, calendar.startOf(week.plusWeeks(1)));
        }
        boolean hasSelection = effectiveFrom.isBefore(effectiveEnd);
        List<Bucket> buckets = hasSelection
                ? buckets(calendar, granularity, effectiveFrom, effectiveEnd) : List.of();
        return new L1RegistrationCohortQuery(selection, granularity, cutoff, storageFrom, storageEnd,
                hasSelection ? effectiveFrom : null, hasSelection ? effectiveEnd : null, buckets,
                storageTime(serverNow).minusMonths(MIN_RETENTION_MONTHS));
    }

    public String window() { return selection.window(); }
    public String from() { return selection.from(); }
    public String to() { return selection.to(); }
    public String cohort() { return selection.cohort(); }
    public String phase() { return selection.phase(); }
    public String locale() { return selection.locale(); }
    public String ref() { return selection.ref(); }

    public LocalDateTime observationEnd() { return storageTime(asOf); }

    public void requireBucket(String bucketKey) {
        if (bucketKey != null && buckets.stream().noneMatch(bucket -> bucket.key().equals(bucketKey))) {
            throw new IllegalArgumentException("L1_BUCKET_INVALID");
        }
    }

    public Map<String, Object> auditFields() {
        Map<String, Object> fields = new LinkedHashMap<>(selection.auditFields());
        fields.put("granularity", granularity);
        fields.put("asOf", asOf.toString());
        return fields;
    }

    static Instant instant(LocalDateTime storageTime) {
        return storageTime.atZone(BusinessCalendar.STORAGE_ZONE).toInstant();
    }

    private static LocalDateTime storageTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, BusinessCalendar.STORAGE_ZONE);
    }

    private static Instant observationAsOf(String asOf, Instant serverNow) {
        if (asOf == null) return serverNow;
        try {
            Instant parsed = Instant.parse(asOf);
            if (asOf.endsWith("Z") && !parsed.isAfter(serverNow)
                    && storageTime(parsed).getYear() >= 1000 && storageTime(parsed).getYear() <= 9999) {
                return parsed;
            }
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("L1_AS_OF_INVALID", exception);
        }
        throw new IllegalArgumentException("L1_AS_OF_INVALID");
    }

    private static LocalDate parseDate(String date) {
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("L1_CUSTOM_WINDOW_INVALID");
        }
        // L1ExportSelection already checked the date and custom range.
        return LocalDate.parse(date);
    }

    private static LocalDate parseWeek(String key) {
        if (!key.matches("[0-9]{4}-W[0-9]{2}")) {
            throw new IllegalArgumentException("L1_COHORT_INVALID");
        }
        int year = Integer.parseInt(key.substring(0, 4));
        int week = Integer.parseInt(key.substring(6));
        LocalDate start = monday(LocalDate.of(year, 1, 4)).plusWeeks(week - 1L);
        if (year < 1 || !weekKey(start).equals(key)) {
            throw new IllegalArgumentException("L1_COHORT_INVALID");
        }
        return start;
    }

    private static List<Bucket> buckets(BusinessCalendar.Track calendar, String granularity,
            Instant selectedFrom, Instant selectedEnd) {
        boolean weekly = "registration_week".equals(granularity);
        LocalDate first = calendar.dateAt(selectedFrom);
        LocalDate startDate = weekly ? monday(first) : first.withDayOfMonth(1);
        Instant start = calendar.startOf(startDate);
        List<Bucket> result = new ArrayList<>();
        while (start.isBefore(selectedEnd)) {
            if (result.size() == MAX_POINTS) {
                throw new IllegalArgumentException("L1_COHORT_POINT_LIMIT_EXCEEDED");
            }
            LocalDate endDate = weekly ? startDate.plusWeeks(1) : startDate.plusMonths(1);
            Instant end = calendar.startOf(endDate);
            Instant from = later(start, selectedFrom);
            Instant to = earlier(end, selectedEnd);
            result.add(new Bucket(weekly ? weekKey(startDate) : YearMonth.from(startDate).toString(),
                    startDate, endDate, start, end, from, to));
            startDate = endDate;
            start = end;
        }
        return List.copyOf(result);
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String weekKey(LocalDate date) {
        return String.format(Locale.ROOT, "%04d-W%02d", date.get(WeekFields.ISO.weekBasedYear()),
                date.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }

    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }

    public record Bucket(String key, LocalDate startDate, LocalDate endDateExclusive,
            Instant from, Instant toExclusive, Instant selectedFrom, Instant selectedToExclusive) {
        public boolean partial() { return !from.equals(selectedFrom) || !toExclusive.equals(selectedToExclusive); }
    }
}
