package ffdd.opsconsole.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;

/** Read-side semantic view of immutable legacy proofs; never changes signed bytes or grants authority. */
public final class LegacyUvtProof {
    private static final Set<String> CURRENCY_FIELDS = Set.of("asset", "currency", "from_asset", "to_asset",
            "payment_asset", "rank_currency", "fromAsset", "toAsset", "paymentAsset", "rankCurrency");
    private LegacyUvtProof() { }

    public static String currency(String recorded) { return "NEX".equals(recorded) ? "UVT" : recorded; }

    /** Only the forward legacy rename is compatible; unrelated or reverse mismatches remain invalid. */
    public static boolean matches(String recorded, String current) {
        return Objects.equals(recorded, current) || "NEX".equals(recorded) && "UVT".equals(current);
    }

    /** Call only after original integrity checks, or retain the original for those checks before accepting this view. */
    public static JsonNode current(JsonNode original) {
        JsonNode copy = original.deepCopy();
        project(copy);
        return copy;
    }

    private static void project(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(field -> {
                if (CURRENCY_FIELDS.contains(field.getKey()) && field.getValue().isTextual()
                        && "NEX".equals(field.getValue().textValue())) object.put(field.getKey(), "UVT");
                else project(field.getValue());
            });
        } else if (node.isArray()) node.forEach(LegacyUvtProof::project);
    }
}
