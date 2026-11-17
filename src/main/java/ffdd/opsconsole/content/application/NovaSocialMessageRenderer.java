package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.domain.NotificationTranslation;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

final class NovaSocialMessageRenderer {
    static final Set<String> SUPPORTED_PLACEHOLDERS = Set.of(
            "{actor}", "{name}", "{city}", "{amount}", "{eventType}");

    private NovaSocialMessageRenderer() {
    }

    static RenderedMessage render(NovaTemplateView template, NovaSocialEventView event, String language) {
        if (hasRetiredSourceNote(template)) {
            throw new BizException(422, "NOVA_TEMPLATE_PLACEHOLDER_UNSUPPORTED");
        }
        Map<String, String> values = Map.of(
                "actor", safe(event.actorDisplay()),
                "name", safe(event.actorDisplay()),
                "city", safe(event.cityDisplay()),
                "amount", safe(event.amountDisplay()),
                "eventType", safe(event.eventType()));
        var translated = template.translations().get(language.toLowerCase(java.util.Locale.ROOT));
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
        return new RenderedMessage(replace(titleTemplate, values), renderBody(bodyTemplate, values, event, language));
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

    private static String eventSummary(NovaSocialEventView event, String language) {
        String actor = safe(event.actorDisplay());
        String city = safe(event.cityDisplay());
        String amount = safe(event.amountDisplay());
        if ("VI".equals(language)) {
            return switch (event.eventType()) {
                case "withdrawal" -> actor + " tại " + city + " vừa nhận khoản rút " + amount;
                case "vrank" -> actor + " vừa được thăng hạng V";
                case "genesis" -> actor + " tại " + city + " vừa hoàn tất giao dịch Genesis " + amount;
                case "newUsers" -> city + " vừa ghi nhận " + amount.replace("人", "người dùng") + " mới";
                default -> "Hoạt động thực đã được xác minh trên UVEL";
            };
        }
        if (!"ZH".equals(language)) {
            return switch (event.eventType()) {
                case "withdrawal" -> actor + " in " + city + " received a " + amount + " withdrawal";
                case "vrank" -> actor + " advanced to a new V rank";
                case "genesis" -> actor + " in " + city + " completed a Genesis trade of " + amount;
                case "newUsers" -> city + " recorded " + amount.replace("人", "users");
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
