package ffdd.opsconsole.bi.domain;

import java.util.Map;

public final class BiSampleSuppression {
    private BiSampleSuppression() { }

    public static boolean belowThreshold(long users) {
        return users < 5;
    }

    public static Map<String, Object> protect(Map<String, Object> row, boolean suppressed, String... fields) {
        if (suppressed) {
            row.put("suppressed", true);
            for (String field : fields) row.put(field, null);
        }
        return row;
    }
}
