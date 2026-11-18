package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders the frozen H4 review snapshots, without changing the approved command or persisted columns. */
final class H4TicketReviewText {
    private static final Set<String> TECHNICAL = Set.of("expectedSignature", "wheelSignature", "expectedRevision",
            "updated_at", "created_at", "revision", "is_deleted", "entries", "localized", "translations");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("eventCode", "活动编号"), Map.entry("event_code", "活动编号"),
            Map.entry("enabled", "启用"), Map.entry("q_status", "活动状态"), Map.entry("status", "状态"),
            Map.entry("startsAt", "开始时间"), Map.entry("starts_at", "开始时间"),
            Map.entry("endsAt", "结束时间"), Map.entry("ends_at", "结束时间"),
            Map.entry("drawAt", "开奖时间"), Map.entry("draw_at", "开奖时间"),
            Map.entry("rules", "活动规则"), Map.entry("rules_json", "活动规则"),
            Map.entry("rewardName", "奖项名称"), Map.entry("reward_name", "奖项名称"),
            Map.entry("rewardKind", "奖励类型"), Map.entry("rewardType", "奖励类型"),
            Map.entry("reward_kind", "奖励类型"), Map.entry("reward_type", "奖励类型"),
            Map.entry("rewardAmount", "奖励数量"), Map.entry("reward_amount", "奖励数量"),
            Map.entry("probabilityPct", "中奖概率"), Map.entry("probability_pct", "中奖概率"),
            Map.entry("probabilities", "各奖档概率"), Map.entry("dailyStock", "每日库存"),
            Map.entry("daily_stock", "每日库存"), Map.entry("tierName", "奖档"), Map.entry("tier_name", "奖档"),
            Map.entry("tiers", "奖档配置"), Map.entry("prizes", "奖项"), Map.entry("amount", "奖励金额"),
            Map.entry("quantity", "数量"), Map.entry("realOutflow", "实际资金支出"),
            Map.entry("guardKey", "护栏项目"), Map.entry("guardValue", "护栏值"), Map.entry("guard_value", "护栏值"),
            Map.entry("value", "拟调整值"), Map.entry("expectedValue", "原值"),
            Map.entry("rewardNex", "UVT 奖励"), Map.entry("budgetNex", "UVT 预算"), Map.entry("poolUsdt", "USDT 奖池"));

    private H4TicketReviewText() {}

    static String render(ObjectMapper mapper, String frozenJson) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = mapper.readTree(frozenJson);
        if (root == null || !root.isObject()) return frozenJson;
        List<String> values = new ArrayList<>();
        append(mapper, root, "", values);
        return values.isEmpty() ? frozenJson : String.join("；", values);
    }

    private static void append(ObjectMapper mapper, JsonNode node, String path, List<String> values) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode part = node.get(i);
                String identifier = part.path("tierName").asText(part.path("tier_name").asText("第 " + (i + 1) + " 项"));
                append(mapper, part, path + " / " + identifier, values);
            }
            return;
        }
        if (!node.isObject()) {
            if (!node.isNull()) values.add(path + "：" + (node.isBoolean() ? node.asBoolean() ? "是" : "否" : node.asText()));
            return;
        }
        String asset = node.path("rewardKind").asText(node.path("reward_kind").asText(
                node.path("rewardType").asText(node.path("reward_type").asText(""))));
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            if (TECHNICAL.contains(key) || field.getValue().isNull()) continue;
            JsonNode value = field.getValue();
            String label = LABELS.getOrDefault(key, key);
            String next = path.isEmpty() ? label : path + " / " + label;
            if (Set.of("tiers", "rules_json").contains(key) && value.isTextual()) {
                try { value = mapper.readTree(value.asText()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* Keep the frozen original text. */ }
                if (value == null) continue;
            }
            if (Set.of("rewardAmount", "reward_amount", "amount").contains(key)
                    && Set.of("UVT", "USDT", "NEX").contains(asset.toUpperCase(java.util.Locale.ROOT))) {
                values.add(next + "：" + value.asText() + " " + asset.toUpperCase(java.util.Locale.ROOT));
            } else if (Set.of("probabilityPct", "probability_pct").contains(key) || path.endsWith("各奖档概率")) {
                values.add(next + "：" + value.asText() + "%");
            } else {
                append(mapper, value, next, values);
            }
        }
    }
}
