package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.shared.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class A4RuntimePolicyService {
    public static final String DAY0_KEY = "admin.a4.event.kpi.day0";
    public static final String EVENT_RETENTION_KEY = "admin.a4.event.kpi.event_retention";
    public static final String SAMPLING_KEY = "admin.a4.event.kpi.sampling";
    private static final java.util.regex.Pattern BROWSING_SAMPLING = java.util.regex.Pattern
            .compile("(?:^|[·;,，；]\\s*)浏览/会话\\s*(100|[1-9]?[0-9])\\s*%(?:\\s*[·;,，；]|$)");
    private static final java.util.regex.Pattern PROTECTED_SAMPLING = java.util.regex.Pattern
            .compile("资金/风控/转化\\s*100\\s*%");
    private final PlatformConfigRepository repository;

    public int day0Seconds() {
        return bounded(DAY0_KEY, "^(\\d{1,4})\\s*(?:秒|s|sec|seconds?)?$", 30, 600,
                "A4_DAY0_POLICY_UNAVAILABLE", "A4_DAY0_POLICY_INVALID");
    }

    public int eventRetentionMonths() {
        return bounded(EVENT_RETENTION_KEY, "^(\\d{1,3})\\s*(?:个月|月|months?)?$", 13, 60,
                "A4_EVENT_RETENTION_POLICY_UNAVAILABLE", "A4_EVENT_RETENTION_POLICY_INVALID");
    }

    /** Runtime sampling authority shared with the A4 admin parameter endpoint. */
    public int samplingPercent(String familyKey, boolean serverAuthoritative) {
        String family = familyKey == null ? "" : familyKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (serverAuthoritative || java.util.Set.of(
                "conversion", "risk", "funds", "finance", "money", "monetization").contains(family)) {
            return 100;
        }
        String value = repository.findActiveByKey(SAMPLING_KEY).map(PlatformConfigItem::configValue)
                .filter(StringUtils::hasText).map(String::trim)
                .orElseThrow(() -> new BizException(503, "A4_SAMPLING_POLICY_UNAVAILABLE"));
        java.util.regex.Matcher matcher = BROWSING_SAMPLING.matcher(value);
        if (!matcher.find() || !PROTECTED_SAMPLING.matcher(value).find()) {
            throw new BizException(503, "A4_SAMPLING_POLICY_INVALID");
        }
        int percent = Integer.parseInt(matcher.group(1));
        if (percent < 0 || percent > 100) throw new BizException(503, "A4_SAMPLING_POLICY_INVALID");
        return percent;
    }

    private int bounded(String key, String format, int min, int max, String missing, String invalid) {
        String value = repository.findActiveByKey(key).map(PlatformConfigItem::configValue)
                .filter(StringUtils::hasText).map(String::trim)
                .orElseThrow(() -> new BizException(503, missing));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(format, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value);
        if (!matcher.matches()) throw new BizException(503, invalid);
        try {
            int parsed = Integer.parseInt(matcher.group(1));
            if (parsed < min || parsed > max) throw new NumberFormatException("range");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BizException(503, invalid);
        }
    }
}
