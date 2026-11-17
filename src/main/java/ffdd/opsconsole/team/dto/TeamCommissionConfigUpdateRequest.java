package ffdd.opsconsole.team.dto;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record TeamCommissionConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator,
        Long expectedVersion,
        Long expectedMaterialRevision,
        String expectedRevision,
        String expectedValue) {
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator){this(key,value,reason,operator,null,null,null,null);}
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator,Long expectedVersion){this(key,value,reason,operator,expectedVersion,null,null,null);}
    public TeamCommissionConfigUpdateRequest(String key,String value,String reason,String operator,Long expectedVersion,Long expectedMaterialRevision){this(key,value,reason,operator,expectedVersion,expectedMaterialRevision,null,null);}

    /** Explicit complete L1-L7 values for the one-time A2 bootstrap; no value is defaulted. */
    public record UnilevelBootstrap(Long expectedVersion, List<Layer> layers) {
        public record Layer(Integer layerNo, String usdtPct, String nexPerUsd) { }

        public static UnilevelBootstrap from(Map<String, Object> params) {
            if (params == null) throw invalid();
            Object version = params.get("expectedVersion");
            if (!(version instanceof Number number) || number.doubleValue() != 0d) throw invalid();
            if (!(params.get("layers") instanceof List<?> raw) || raw.size() != 7) throw invalid();
            List<Layer> layers = new ArrayList<>(7);
            for (int index = 0; index < raw.size(); index++) {
                if (!(raw.get(index) instanceof Map<?, ?> item)) throw invalid();
                Object layerNo = item.get("layerNo");
                if (!(layerNo instanceof Number number) || number.intValue() != index + 1
                        || number.doubleValue() != index + 1) throw invalid();
                String usdtPct = text(item.get("usdtPct"));
                String nexPerUsd = text(item.get("nexPerUsd"));
                if (usdtPct == null || nexPerUsd == null) throw invalid();
                layers.add(new Layer(index + 1, usdtPct, nexPerUsd));
            }
            return new UnilevelBootstrap(0L, List.copyOf(layers));
        }

        private static String text(Object value) {
            return value instanceof String text && !text.isBlank() && text.equals(text.trim()) ? text : null;
        }

        private static BizException invalid() {
            return new BizException(422, "F2_UNILEVEL_BOOTSTRAP_INVALID");
        }
    }
}
