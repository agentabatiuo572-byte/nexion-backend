package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.NovaSocialEventView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

final class NovaSocialMessageRenderer {
    static final Set<String> SUPPORTED_PLACEHOLDERS = Set.of(
            "{actor}", "{name}", "{city}", "{amount}", "{eventType}", "{sourceNote}");

    private NovaSocialMessageRenderer() {
    }

    static RenderedMessage render(NovaTemplateView template, NovaSocialEventView event, String language) {
        Map<String, String> values = Map.of(
                "actor", safe(event.actorDisplay()),
                "name", safe(event.actorDisplay()),
                "city", safe(event.cityDisplay()),
                "amount", safe(event.amountDisplay()),
                "eventType", safe(event.eventType()),
                "sourceNote", safe(event.sourceNote()));
        String titleTemplate = switch (language) {
            case "ZH" -> template.titleZh();
            case "EN" -> StringUtils.hasText(template.titleEn()) ? template.titleEn() : template.titleVi();
            default -> template.titleVi();
        };
        String bodyTemplate = switch (language) {
            case "ZH" -> template.bodyZh();
            case "EN" -> StringUtils.hasText(template.bodyEn()) ? template.bodyEn() : template.bodyVi();
            default -> template.bodyVi();
        };
        return new RenderedMessage(replace(titleTemplate, values), renderBody(bodyTemplate, values, event, language));
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
                default -> "Hoạt động thực đã được xác minh trên Nexion";
            };
        }
        if ("EN".equals(language)) {
            return switch (event.eventType()) {
                case "withdrawal" -> actor + " in " + city + " received a " + amount + " withdrawal";
                case "vrank" -> actor + " advanced to a new V rank";
                case "genesis" -> actor + " in " + city + " completed a Genesis trade of " + amount;
                case "newUsers" -> city + " recorded " + amount.replace("人", "users");
                default -> "Verified activity on Nexion";
            };
        }
        return switch (event.eventType()) {
            case "withdrawal" -> actor + " 在 " + city + " 完成一笔 " + amount + " 提现到账";
            case "vrank" -> actor + " 的 V 等级刚刚晋升";
            case "genesis" -> actor + " 在 " + city + " 完成一笔 " + amount + " Genesis 成交";
            case "newUsers" -> city + " 刚刚新增 " + amount;
            default -> "Nexion 已验证真实动态";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record RenderedMessage(String title, String body) {
    }
}
