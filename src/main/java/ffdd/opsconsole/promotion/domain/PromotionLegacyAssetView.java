package ffdd.opsconsole.promotion.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.common.domain.LegacyUvtProof;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;

/** Current asset semantics of an already verified frozen promotion; never edits signed/stored bytes. */
public final class PromotionLegacyAssetView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private PromotionLegacyAssetView() { }

    public static String asset(Object recorded) { return LegacyUvtProof.currency(text(recorded)); }

    public static Map<String,Object> reward(Map<String,Object> original) {
        var result = new LinkedHashMap<>(original);
        result.put("type", asset(original.get("type")));
        return result;
    }

    public static Map<String,Object> verifiedSnapshot(Map<String,Object> row) {
        var snapshot = parse(row.get("snapshot_json"));
        require(hash(snapshot).equals(row.get("snapshot_hash")), "PROMOTION_REWARD_SNAPSHOT_INVALID");
        return snapshot;
    }

    /** Caller checks the original content/quote hash before requesting this schema-validation view. */
    public static JsonNode validationView(Object original) {
        JsonNode view = LegacyUvtProof.current(JSON.valueToTree(original));
        projectRewards(view);
        return view;
    }

    private static void projectRewards(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(field -> {
                JsonNode value = field.getValue();
                if (Set.of("buyerReward", "inviterReward", "reward").contains(field.getKey())
                        && value instanceof ObjectNode reward && "NEX".equals(reward.path("type").textValue())) {
                    reward.put("type", "UVT");
                }
                projectRewards(value);
            });
        } else if (node.isArray()) node.forEach(PromotionLegacyAssetView::projectRewards);
    }
}
