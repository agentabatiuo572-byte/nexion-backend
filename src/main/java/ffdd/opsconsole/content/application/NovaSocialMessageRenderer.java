package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.domain.NotificationTranslation;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

final class NovaSocialMessageRenderer {
    static final Set<String> SUPPORTED_PLACEHOLDERS = Set.of(
            "{actor}", "{name}", "{city}", "{amount}", "{eventType}");
    // Java 17 的 Unicode 数据早于 CJK 扩展 H、I、J。
    private static final Pattern HAN = Pattern.compile(
            "[\\p{IsHan}\\x{2EBF0}-\\x{2EE5F}\\x{31350}-\\x{3347F}]");
    private static final String USERS_UNIT_CODE = " USERS";
    /** NovaMapper.newUserSourceEvents 生成的固定小时批号：newUsers:yyyyMMddHH。 */
    private static final Pattern HOURLY_REGISTRATION_BATCH = Pattern.compile("newUsers:\\d{10}");
    /** 两代聚合备注：已投递数据里的旧中文说明，与本版素材改成的代码值。 */
    private static final String LEGACY_AGGREGATE_NOTE = "完整小时注册用户聚合";
    private static final String AGGREGATE_NOTE_CODE = "HOURLY_REGISTRATION_AGGREGATE";
    private static final String LEGACY_ALL_REGIONS = "全网";
    private static final String LEGACY_UNKNOWN_REGION = "未知地区";
    private static final String LEGACY_ANONYMOUS = "匿名用户";
    private static final String LEGACY_USERS_UNIT = " 人";

    private NovaSocialMessageRenderer() {
    }

    static RenderedMessage render(NovaTemplateView template, NovaSocialEventView event, String language) {
        if (hasRetiredSourceNote(template)) {
            throw new BizException(422, "NOVA_TEMPLATE_PLACEHOLDER_UNSUPPORTED");
        }
        language = language.toUpperCase(Locale.ROOT);
        Map<String, String> values = localizedValues(event, language);
        var translated = template.translations().get(language.toLowerCase(Locale.ROOT));
        String titleTemplate = switch (language) {
            case "ZH" -> template.titleZh();
            case "VI" -> template.titleVi();
            default -> translated != null && StringUtils.hasText(translated.title()) ? translated.title()
                    : StringUtils.hasText(template.titleEn()) ? template.titleEn() : "UVEL notification";
        };
        String bodyTemplate = switch (language) {
            case "ZH" -> template.bodyZh();
            case "VI" -> template.bodyVi();
            default -> translated != null && StringUtils.hasText(translated.body()) ? translated.body()
                    : StringUtils.hasText(template.bodyEn()) ? template.bodyEn() : "Open UVEL to view the update.";
        };
        String title = replace(titleTemplate, values);
        String body = renderBody(bodyTemplate, values, event, language);
        if (hasHanInEnglishOrVietnamese(title, language)) {
            title = "VI".equals(language) ? "Hoạt động thực trên UVEL" : "Verified UVEL activity";
        }
        if (hasHanInEnglishOrVietnamese(body, language)) {
            body = eventSummary(event, language);
            if (containsHan(body)) {
                body = "VI".equals(language) ? "Mở UVEL để xem cập nhật." : "Open UVEL to view the update.";
            }
        }
        return new RenderedMessage(title, body);
    }

    static boolean hasRetiredSourceNote(NovaTemplateView template) {
        return Stream.of(template.titleZh(), template.bodyZh(), template.titleVi(), template.bodyVi(),
                        template.titleEn(), template.bodyEn())
                .anyMatch(text -> text != null && text.contains("{sourceNote}"))
                || hasRetiredSourceNote(template.translations());
    }

    static boolean hasRetiredSourceNote(Map<String, NotificationTranslation> translations) {
        return translations.values().stream().anyMatch(value ->
                value.title().contains("{sourceNote}") || value.body().contains("{sourceNote}"));
    }

    private static String renderBody(String template, Map<String, String> values,
                                     NovaSocialEventView event, String language) {
        boolean hasEventPlaceholder = values.keySet().stream()
                .anyMatch(key -> safe(template).contains("{" + key + "}"));
        String rendered = replace(template, values);
        return hasEventPlaceholder ? rendered : rendered + " · " + eventSummary(event, language);
    }

    private static String replace(String template, Map<String, String> values) {
        String result = safe(template);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * BUG-520: 素材只存语言无关的值（聚合城市留空、数量单位 USERS、匿名为空），这里按收件人语言出字。
     * 「全网」只看注册聚合的可信来源身份，不看会被掩码破坏的城市字符串；
     * 旧数据里残留的中文兜底（未掩码的全网、未知地区、匿名用户、数量里的人）也在这里归一。
     */
    static Map<String, String> localizedValues(NovaSocialEventView event, String language) {
        String actor = actorText(event.actorDisplay(), language);
        return Map.of(
                "actor", actor,
                "name", actor,
                "city", cityText(event, language),
                "amount", amountText(event.amountDisplay(), language),
                "eventType", safe(event.eventType()));
    }

    /**
     * BUG-520: 只有「我们自己的固定小时注册聚合」才按全网出字。判据要求三件事同时成立：
     * 1) 事件类型/来源三标签（newUsers / UVEL_CORE / nx_user）；
     * 2) sourceEventId 是 NovaMapper.newUserSourceEvents 生成的固定小时批号 newUsers:yyyyMMddHH；
     * 3) sourceNote 是我们自己的聚合备注 —— 旧数据「完整小时注册用户聚合」或本版的
     *    HOURLY_REGISTRATION_AGGREGATE。
     * 受信适配器入口(ingestTrustedSocialEvent)对 id 与备注都不设形状限制，同标签、同小时形状
     * 但带真实地区备注的事件必须保留原掩码地区，不能改成全网。
     */
    static boolean registrationAggregate(NovaSocialEventView event) {
        if (!"newUsers".equals(safe(event.eventType()))
                || !"UVEL_CORE".equalsIgnoreCase(safe(event.sourceSystem()))
                || !"nx_user".equalsIgnoreCase(safe(event.sourceTable()))
                || !HOURLY_REGISTRATION_BATCH.matcher(safe(event.sourceEventId())).matches()) {
            return false;
        }
        String note = safe(event.sourceNote());
        return AGGREGATE_NOTE_CODE.equals(note) || LEGACY_AGGREGATE_NOTE.equals(note);
    }

    static String actorText(String raw, String language) {
        String value = safe(raw);
        if (!StringUtils.hasText(value) || LEGACY_ANONYMOUS.equals(value)
                || hasHanInEnglishOrVietnamese(value, language)) {
            return switch (language) {
                case "ZH" -> LEGACY_ANONYMOUS;
                case "VI" -> "Người dùng ẩn danh";
                default -> "Anonymous user";
            };
        }
        return value;
    }

    static String cityText(NovaSocialEventView event, String language) {
        if (registrationAggregate(event)) return allRegions(language);
        String value = safe(event == null ? null : event.cityDisplay());
        // 旧数据里未掩码的「全网」哨兵同样按全站聚合出字（掩码后的「全***」不在此匹配，只认身份）。
        if (LEGACY_ALL_REGIONS.equals(value)) return allRegions(language);
        if (!StringUtils.hasText(value) || LEGACY_UNKNOWN_REGION.equals(value)
                || hasHanInEnglishOrVietnamese(value, language)) {
            return unknownRegion(language);
        }
        return value;
    }

    private static String allRegions(String language) {
        return switch (language) {
            case "ZH" -> LEGACY_ALL_REGIONS;
            case "VI" -> "Toàn hệ thống";
            default -> "All regions";
        };
    }

    private static String unknownRegion(String language) {
        return switch (language) {
            case "ZH" -> LEGACY_UNKNOWN_REGION;
            case "VI" -> "Khu vực không xác định";
            default -> "Unknown region";
        };
    }

    static String amountText(String raw, String language) {
        String value = safe(raw);
        String users = switch (language) {
            case "ZH" -> "人";
            case "VI" -> "người dùng";
            default -> "users";
        };
        if (value.endsWith(USERS_UNIT_CODE)) return value.substring(0, value.length() - USERS_UNIT_CODE.length()) + " " + users;
        if (value.endsWith(LEGACY_USERS_UNIT)) return value.substring(0, value.length() - LEGACY_USERS_UNIT.length()) + " " + users;
        return value;
    }

    /** BUG-520: 模板与渲染结果的语言检查（非中文不得含汉字）。 */
    static boolean containsHan(String text) {
        return text != null && HAN.matcher(text).find();
    }

    private static boolean hasHanInEnglishOrVietnamese(String text, String language) {
        return ("EN".equals(language) || "VI".equals(language)) && containsHan(text);
    }

    private static String eventSummary(NovaSocialEventView event, String language) {
        String actor = actorText(event.actorDisplay(), language);
        String city = cityText(event, language);
        String amount = amountText(event.amountDisplay(), language);
        if ("VI".equals(language)) {
            return switch (event.eventType()) {
                case "withdrawal" -> actor + " tại " + city + " vừa nhận khoản rút " + amount;
                case "vrank" -> actor + " vừa được thăng hạng V";
                case "genesis" -> actor + " tại " + city + " vừa hoàn tất giao dịch Genesis " + amount;
                case "newUsers" -> city + " vừa ghi nhận " + amount + " mới";
                default -> "Hoạt động thực đã được xác minh trên UVEL";
            };
        }
        if (!"ZH".equals(language)) {
            return switch (event.eventType()) {
                case "withdrawal" -> actor + " in " + city + " received a " + amount + " withdrawal";
                case "vrank" -> actor + " advanced to a new V rank";
                case "genesis" -> actor + " in " + city + " completed a Genesis trade of " + amount;
                case "newUsers" -> city + " recorded " + amount;
                default -> "Verified activity on UVEL";
            };
        }
        return switch (event.eventType()) {
            case "withdrawal" -> actor + " 在 " + city + " 完成一笔 " + amount + " 提现到账";
            case "vrank" -> actor + " 的 V 等级刚刚晋升";
            case "genesis" -> actor + " 在 " + city + " 完成一笔 " + amount + " Genesis 成交";
            case "newUsers" -> city + " 刚刚新增 " + amount;
            default -> "UVEL 已验证真实动态";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record RenderedMessage(String title, String body) {
    }
}
