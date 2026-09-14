package ffdd.opsconsole.bi.domain;

import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The selected L1 read scope, validated independently of client KPI values. */
public record L1ExportSelection(
        String window, String from, String to, String cohort, String phase, String locale, String ref) {

    public static L1ExportSelection parse(String window, String from, String to,
            String cohort, String phase, String locale, String ref, Instant now) {
        return parse(window, from, to, cohort, phase, locale, ref, L1KpiAnalytics.businessDate(now));
    }

    public static L1ExportSelection parse(String window, String from, String to,
            String cohort, String phase, String locale, String ref, LocalDate today) {
        String period = text(window).toLowerCase(Locale.ROOT);
        if (period.isEmpty()) period = "7d";
        if (!java.util.Set.of("1d", "7d", "30d", "custom").contains(period)) {
            throw new IllegalArgumentException("L1_WINDOW_INVALID");
        }
        String start = text(from), end = text(to);
        if ("custom".equals(period)) {
            try {
                LocalDate first = LocalDate.parse(start), last = LocalDate.parse(end);
                if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) > 400 || last.isAfter(today)) {
                    throw new IllegalArgumentException("L1_CUSTOM_WINDOW_INVALID");
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("L1_CUSTOM_WINDOW_INVALID", error);
            }
        } else if (!start.isEmpty() || !end.isEmpty()) {
            throw new IllegalArgumentException("L1_CUSTOM_WINDOW_NOT_SUPPORTED");
        }
        String selectedCohort = text(cohort);
        String selectedPhase = text(phase).toUpperCase(Locale.ROOT);
        String selectedLocale = text(locale).toLowerCase(Locale.ROOT);
        String selectedRef = text(ref);
        if (!selectedCohort.isEmpty() && !selectedCohort.matches("^\\d{4}-(?:W(?:0[1-9]|[1-4]\\d|5[0-3])|(?:0[1-9]|1[0-2]))$")) {
            throw new IllegalArgumentException("L1_COHORT_INVALID");
        }
        if (!selectedPhase.isEmpty() && !selectedPhase.matches("^P[1-6]$")) {
            throw new IllegalArgumentException("L1_PHASE_INVALID");
        }
        if (!selectedLocale.isEmpty() && !selectedLocale.matches("^[a-z]{2,8}(?:-[a-z0-9]{2,8})?$")) {
            throw new IllegalArgumentException("L1_LOCALE_INVALID");
        }
        if (!selectedRef.isEmpty() && (selectedRef.length() > 96 || !selectedRef.matches("^[\\p{L}\\p{N}._:-]+$"))) {
            throw new IllegalArgumentException("L1_REF_INVALID");
        }
        return new L1ExportSelection(period, start, end, selectedCohort, selectedPhase, selectedLocale, selectedRef);
    }

    public String analyticsWindow() {
        return "custom".equals(window) ? "custom|" + from + "|" + to : window;
    }

    public Map<String, Object> auditFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("window", window);
        fields.put("from", from);
        fields.put("to", to);
        fields.put("cohort", cohort);
        fields.put("phase", phase);
        fields.put("locale", locale);
        fields.put("ref", ref);
        return fields;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
