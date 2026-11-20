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

    static String probabilityChanges(ObjectMapper mapper, Object previous, Object proposed, boolean before)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode state = mapper.valueToTree(previous);
        JsonNode requested = mapper.valueToTree(proposed);
        if (state == null || requested == null) return null;
        JsonNode tiers = state.path("tiers");
        if (tiers.isTextual()) tiers = mapper.readTree(tiers.asText());
        var probabilities = requested.path("probabilities");
        if (tiers == null || !tiers.isArray() || !probabilities.isObject()) return null;
        var previousByTier = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        for (JsonNode tier : tiers) {
            String key = tier.path("tier").asText("");
            var amount = decimal(tier.path("prob"));
            if (key.isBlank() || amount == null || previousByTier.putIfAbsent(key, amount) != null) return null;
        }
        List<String> changes = new ArrayList<>();
        var fields = probabilities.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            var oldValue = previousByTier.get(field.getKey());
            var newValue = decimal(field.getValue());
            if (oldValue == null || newValue == null) return null;
            if (oldValue.compareTo(newValue) != 0) {
                var amount = before ? oldValue : newValue;
                changes.add(field.getKey() + "中奖概率：" + amount.stripTrailingZeros().toPlainString() + "%");
            }
        }
        return changes.isEmpty() ? null : String.join("；", changes);
    }

    static String legacyRewardChange(ObjectMapper mapper, Object previous, Object proposed, boolean before) {
        JsonNode oldState = mapper.valueToTree(previous);
        JsonNode newState = mapper.valueToTree(proposed);
        if (oldState == null || newState == null) return null;
        String oldAsset = oldState.path("rewardType").asText("").toUpperCase(java.util.Locale.ROOT);
        String newAsset = newState.path("rewardType").asText("").toUpperCase(java.util.Locale.ROOT);
        Set<String> assets = Set.of("USDT", "UVT", "NEX");
        if (!assets.contains(oldAsset) || !assets.contains(newAsset)) return null;
        var oldAmount = decimal(oldState.path("rewardAmount"));
        var newAmount = decimal(newState.path("rewardAmount"));
        if (oldAmount == null || newAmount == null) return null;
        return "活动奖励：" + (before ? oldAmount : newAmount).stripTrailingZeros().toPlainString()
                + " " + (before ? oldAsset : newAsset);
    }

    static String wheelTierChanges(ObjectMapper mapper, Object previous, Object proposed, boolean before)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode state = mapper.valueToTree(previous);
        JsonNode requested = mapper.valueToTree(proposed);
        if (state == null || requested == null) return null;
        JsonNode tiers = state.path("tiers");
        if (tiers.isTextual()) tiers = mapper.readTree(tiers.asText());
        String name = requested.path("tierName").asText("");
        if (tiers == null || !tiers.isArray() || name.isBlank()) return null;
        JsonNode old = null;
        for (JsonNode tier : tiers) {
            if (name.equals(tier.path("tier").asText())) {
                if (old != null) return null;
                old = tier;
            }
        }
        if (old == null) return null;
        List<String> changes = new ArrayList<>();
        String[][] fields = {{"rewardName","reward","奖项名称"},{"probabilityPct","prob","中奖概率"},
                {"rewardKind","kind","奖励类型"},{"rewardAmount","amount","奖励数量"},
                {"dailyStock","dailyStock","每日库存"},{"voucherId","voucherId","代金券"},
                {"realOutflow","real","实际资金支出"}};
        for (String[] field : fields) {
            JsonNode current = old.path(field[1]);
            JsonNode next = requested.path(field[0]);
            if (current.isMissingNode() || next.isMissingNode() || next.isNull()) continue;
            var oldNumber = decimal(current);
            var newNumber = decimal(next);
            if (current.asText().equals(next.asText()) || oldNumber != null && newNumber != null
                    && oldNumber.compareTo(newNumber) == 0) continue;
            JsonNode value = before ? current : next;
            String suffix = "probabilityPct".equals(field[0]) ? "%" : "";
            if ("rewardAmount".equals(field[0])) {
                String asset = (before ? old.path("kind") : requested.path("rewardKind")).asText(old.path("kind").asText(""));
                asset = asset.toUpperCase(java.util.Locale.ROOT);
                if (Set.of("UVT", "USDT", "NEX").contains(asset)) suffix = " " + asset;
            }
            changes.add(name + " / " + field[2] + "：" + value.asText() + suffix);
        }
        return changes.isEmpty() ? null : String.join("；", changes);
    }

    static String remainingOperationChanges(ObjectMapper mapper, String op, Object previous, Object proposed,
            boolean before) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode state = mapper.valueToTree(previous);
        JsonNode request = mapper.valueToTree(proposed);
        if (state == null || request == null || !state.isObject() || !request.isObject()) return null;
        List<String> changes = new ArrayList<>();
        switch (op) {
            case "h4_wheel_guard_create" -> {
                if (!"false".equals(state.path("exists").asText()) || !present(request.path("guardValue"))) return null;
                return before ? "护栏未配置" : "新建护栏：" + request.path("guardLabel").asText(request.path("guardKey").asText())
                        + "；护栏值：" + request.path("guardValue").asText();
            }
            case "h4_wheel_guard_update" -> {
                if (!present(state.path("guard_value")) || !present(request.path("value"))) return null;
                addChange(mapper, changes, "护栏值", state.path("guard_value"), request.path("value"), before);
            }
            case "h4_wheel_tier_create", "h4_wheel_tier_delete" -> {
                String tier = request.path("tierName").asText("");
                if (tier.isBlank()) return null;
                JsonNode tiers = structured(mapper, state.path("tiers"));
                if (!tiers.isArray()) return null;
                JsonNode oldTier = null;
                for (JsonNode item : tiers) {
                    if (tier.equals(item.path("tier").asText())) {
                        if (oldTier != null) return null;
                        oldTier = item;
                    }
                }
                if ("h4_wheel_tier_create".equals(op)) {
                    if (oldTier != null) return null;
                    if (before) return "未配置奖档：" + tier;
                    append(mapper, request, "新增奖档", changes);
                } else {
                    if (oldTier == null) return null;
                    if (!before) return "移除奖档：" + tier;
                    append(mapper, oldTier, "原奖档", changes);
                }
            }
            case "h4_legacy_event_open" -> {
                if (!present(state.path("status")) || !present(request.path("value"))) return null;
                String prior = switch (state.path("status").asText()) {
                    case "0" -> "未开始";
                    case "1" -> "进行中";
                    case "2" -> "已结束";
                    default -> state.path("status").asText();
                };
                changes.add("活动状态：" + (before ? prior : request.path("value").asText()));
            }
            case "h4_event_rule_enable" -> {
                addChange(mapper, changes, "规则启用", state.path("enabled"), request.path("enabled"), before);
                addDateChange(mapper, changes, "开始时间", state.path("starts_at"), request.path("startsAt"), before);
                addDateChange(mapper, changes, "结束时间", state.path("ends_at"), request.path("endsAt"), before);
                addChange(mapper, changes, "活动规则", structured(mapper, state.path("rules_json")), request.path("rules"), before);
            }
            case "h4_genesis_raffle_open" -> {
                if (!present(state.path("status"))) return null;
                changes.add("抽奖状态：" + (before ? state.path("status").asText() : "拟开放"));
                addChange(mapper, changes, "英文标题", state.path("title_en"), request.path("titleEn"), before);
                addChange(mapper, changes, "中文标题", state.path("title_zh"), request.path("titleZh"), before);
                addChange(mapper, changes, "越南语标题", state.path("title_vi"), request.path("titleVi"), before);
                addChange(mapper, changes, "开始时间", state.path("starts_at"), request.path("startsAt"), before);
                addChange(mapper, changes, "开奖时间", state.path("draw_at"), request.path("drawAt"), before);
                addChange(mapper, changes, "奖项", structured(mapper, state.path("prizes_json")), request.path("prizes"), before);
            }
            default -> { return null; }
        }
        return changes.isEmpty() ? null : String.join("；", changes);
    }

    private static void addDateChange(ObjectMapper mapper, List<String> changes, String label,
            JsonNode original, JsonNode proposed, boolean before) {
        if (!present(original) && present(proposed)) {
            changes.add(label + "：" + (before ? "未配置" : proposed.asText()));
        } else {
            addChange(mapper, changes, label, original, proposed, before);
        }
    }

    static String publicationCopy(ObjectMapper mapper, Object frozen) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (frozen == null) return "文案：原票未记录冻结文案";
        JsonNode copy = mapper.valueToTree(frozen);
        if (copy != null && copy.isTextual()) copy = mapper.readTree(copy.asText());
        if (copy == null || !copy.isObject()) return "文案：原票冻结文案不可核对";
        List<String> names = new ArrayList<>();
        var languages = copy.fields();
        while (languages.hasNext()) {
            var language = languages.next();
            JsonNode value = language.getValue();
            if (!value.isObject()) continue;
            for (String field : List.of("name", "description", "rewardName")) {
                if (!value.path(field).isTextual()) continue;
                String label = switch (field) { case "name" -> "标题"; case "description" -> "说明"; default -> "奖项名"; };
                names.add(language.getKey() + " / " + label + "：" + value.path(field).asText());
            }
        }
        return names.isEmpty() ? "文案：未配置" : "冻结文案：" + String.join("；", names);
    }

    private static void addChange(ObjectMapper mapper, List<String> changes, String field,
            JsonNode oldValue, JsonNode newValue, boolean before) {
        if (!present(oldValue) || !present(newValue) || oldValue.equals(newValue)) return;
        if (oldValue.isObject() && newValue.isObject()) {
            var fields = new java.util.LinkedHashSet<String>();
            oldValue.fieldNames().forEachRemaining(fields::add);
            newValue.fieldNames().forEachRemaining(fields::add);
            for (String key : fields) {
                if (TECHNICAL.contains(key)) continue;
                String path = field + " / " + LABELS.getOrDefault(key, key);
                JsonNode oldField = oldValue.path(key);
                JsonNode newField = newValue.path(key);
                JsonNode shown = before ? oldField : newField;
                if (oldField.isMissingNode() != newField.isMissingNode()) {
                    if (shown.isMissingNode()) changes.add(path + "：未配置");
                    else append(mapper, shown, path, changes);
                } else {
                    addChange(mapper, changes, path, oldField, newField, before);
                }
            }
            return;
        }
        var oldNumber = decimal(oldValue);
        var newNumber = decimal(newValue);
        if (oldNumber != null && newNumber != null && oldNumber.compareTo(newNumber) == 0) return;
        append(mapper, before ? oldValue : newValue, field, changes);
    }

    private static boolean present(JsonNode value) {
        return !value.isMissingNode() && !value.isNull() && !(value.isTextual() && "null".equals(value.asText()));
    }

    private static JsonNode structured(ObjectMapper mapper, JsonNode value) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!value.isTextual() || !present(value) || value.asText().isBlank()) return value;
        return mapper.readTree(value.asText());
    }

    private static java.math.BigDecimal decimal(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            return new java.math.BigDecimal(value.asText());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static void append(ObjectMapper mapper, JsonNode node, String path, List<String> values) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode part = node.get(i);
                String identifier = part.path("tierName").asText(part.path("tier_name").asText(part.path("tier").asText("第 " + (i + 1) + " 项")));
                append(mapper, part, path + " / " + identifier, values);
            }
            return;
        }
        if (!node.isObject()) {
            if (!node.isNull()) values.add(path + "：" + (node.isBoolean() ? node.asBoolean() ? "是" : "否" : node.asText()));
            return;
        }
        String asset = node.path("rewardKind").asText(node.path("reward_kind").asText(
                node.path("rewardType").asText(node.path("reward_type").asText(node.path("kind").asText("")))));
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
