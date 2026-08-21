package ffdd.opsconsole.shared.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Server-side evaluator for the structured purchase gate stored with a SKU. */
@Component
public class StorefrontPurchaseGatePolicy {
    private static final Set<String> KEYS = Set.of("rankMin", "activeDirectMin", "teamVolumeMin", "mode",
            "quotaCap", "quotaSold", "quotaPeriod", "enforce");
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Decision evaluate(String raw, Facts facts) {
        if (raw == null || raw.isBlank()) return Decision.open();
        try {
            JsonNode gate = objectMapper.readTree(raw);
            if (!validGate(gate)) return Decision.closed("PURCHASE_GATE_INVALID");
            String mode = text(gate, "mode");
            Integer rank = integer(gate, "rankMin");
            Integer direct = integer(gate, "activeDirectMin");
            BigDecimal volume = decimal(gate, "teamVolumeMin");
            Integer cap = integer(gate, "quotaCap");
            Integer sold = integer(gate, "quotaSold");
            if (rank == null && gate.has("rankMin") || direct == null && gate.has("activeDirectMin")
                    || volume == null && gate.has("teamVolumeMin") || cap == null && gate.has("quotaCap")
                    || sold == null && gate.has("quotaSold")) return Decision.closed("PURCHASE_GATE_INVALID");
            if ((cap != null && sold == null) || (cap == null && sold != null)) return Decision.closed("PURCHASE_GATE_INVALID");
            if (rank != null && (rank < 0 || rank > 12) || direct != null && (direct < 0 || direct > 1_000_000) || cap != null && cap < 1
                    || sold != null && sold < 0 || cap != null && sold != null && sold > cap
                    || volume != null && volume.signum() < 0) return Decision.closed("PURCHASE_GATE_INVALID");
            if (gate.has("quotaPeriod") && (!gate.path("quotaPeriod").isTextual()
                    || !"lifetime".equals(gate.path("quotaPeriod").asText()))) {
                return Decision.closed("PURCHASE_GATE_INVALID");
            }
            if (!gate.path("enforce").asBoolean()) return Decision.open();
            if (cap != null && sold != null && sold >= cap) {
                return Decision.closed("PURCHASE_GATE_SOLD_OUT");
            }
            boolean hasCondition = rank != null || direct != null || volume != null;
            if (hasCondition && facts == null) return Decision.closed("PURCHASE_GATE_FACTS_UNAVAILABLE");
            if (facts == null) facts = new Facts(0, 0, BigDecimal.ZERO);
            boolean eligible;
            if (!hasCondition) {
                eligible = true;
            } else if ("all".equals(mode)) {
                eligible = (rank == null || rank <= facts.rank())
                        && (direct == null || direct <= facts.activeDirect())
                        && (volume == null || volume.compareTo(facts.teamVolumeUsd()) <= 0);
            } else {
                eligible = (rank != null && rank <= facts.rank())
                        || (direct != null && direct <= facts.activeDirect())
                        || (volume != null && volume.compareTo(facts.teamVolumeUsd()) <= 0);
            }
            if (!eligible) return Decision.closed("PURCHASE_GATE_NOT_MET");
            return Decision.open();
        } catch (Exception ex) {
            return Decision.closed("PURCHASE_GATE_INVALID");
        }
    }

    /** True only for a structurally valid, mutable quota pair. */
    public boolean hasQuota(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            JsonNode gate = objectMapper.readTree(raw);
            return validGate(gate) && gate.path("enforce").asBoolean()
                    && gate.has("quotaCap") && gate.has("quotaSold");
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean validGate(JsonNode gate) {
        if (gate == null || !gate.isObject()) return false;
        Iterator<String> names = gate.fieldNames();
        while (names.hasNext()) if (!KEYS.contains(names.next())) return false;
        String mode = text(gate, "mode");
        if (!("all".equals(mode) || "either".equals(mode)) || !gate.path("enforce").isBoolean()) return false;
        Integer rank = integer(gate, "rankMin");
        Integer direct = integer(gate, "activeDirectMin");
        BigDecimal volume = decimal(gate, "teamVolumeMin");
        Integer cap = integer(gate, "quotaCap");
        Integer sold = integer(gate, "quotaSold");
        if (rank == null && gate.has("rankMin") || direct == null && gate.has("activeDirectMin")
                || volume == null && gate.has("teamVolumeMin") || cap == null && gate.has("quotaCap")
                || sold == null && gate.has("quotaSold")) return false;
        if ((cap != null && sold == null) || (cap == null && sold != null)) return false;
        if (rank != null && (rank < 0 || rank > 12) || direct != null && (direct < 0 || direct > 1_000_000)
                || cap != null && cap < 1 || sold != null && sold < 0
                || cap != null && sold != null && sold > cap || volume != null && volume.signum() < 0) return false;
        return !gate.has("quotaPeriod") || gate.path("quotaPeriod").isTextual()
                && "lifetime".equals(gate.path("quotaPeriod").asText());
    }

    private String text(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).asText().trim().toLowerCase(Locale.ROOT) : "";
    }

    private Integer integer(JsonNode node, String key) {
        if (!node.has(key)) return null;
        return node.path(key).canConvertToInt() && node.path(key).isIntegralNumber() ? node.path(key).asInt() : null;
    }

    private BigDecimal decimal(JsonNode node, String key) {
        if (!node.has(key) || !node.path(key).isNumber()) return null;
        return node.path(key).decimalValue();
    }

    public record Facts(int rank, int activeDirect, BigDecimal teamVolumeUsd) {
        public Facts {
            if (rank < 0 || activeDirect < 0 || teamVolumeUsd == null || teamVolumeUsd.signum() < 0) {
                throw new IllegalArgumentException("PURCHASE_GATE_FACTS_INVALID");
            }
        }
    }

    public record Decision(boolean allowed, String code) {
        public static Decision open() { return new Decision(true, null); }
        public static Decision closed(String code) { return new Decision(false, code); }
    }
}
