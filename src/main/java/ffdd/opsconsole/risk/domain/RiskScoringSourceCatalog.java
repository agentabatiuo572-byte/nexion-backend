package ffdd.opsconsole.risk.domain;

import java.util.List;

public final class RiskScoringSourceCatalog {
    private static final List<String> VALUES = List.of(
            "全部启用",
            "停用 C4 实名维度",
            "停用 K2 套利维度",
            "停用异常行为维度");

    private RiskScoringSourceCatalog() {
    }

    public static List<String> values() {
        return VALUES;
    }

    public static boolean contains(String value) {
        return VALUES.contains(value);
    }
}
