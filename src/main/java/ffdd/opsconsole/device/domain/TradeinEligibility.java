package ffdd.opsconsole.device.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BUG-522: E3 置换资格（eligibility）的稳定代码。
 *
 * <p>存储、App 配置与资格判断一律使用代码；运营后台(PC)既有的六档中文名继续作为
 * 展示/输入的兼容映射，历史存量中文值也可识别。未知或空值返回 null，不猜。
 */
public final class TradeinEligibility {
    public static final String ALL = "ALL";
    public static final String L2_PLUS = "L2_PLUS";
    public static final String L3_PLUS = "L3_PLUS";
    public static final String L4_PLUS = "L4_PLUS";
    public static final String L5_PLUS = "L5_PLUS";
    public static final String L6_PLUS = "L6_PLUS";

    private static final Pattern CODE = Pattern.compile("^(ALL|L[2-6]_PLUS)$");
    /** 历史存量：'L2+ 持有者' … 'L6+ 持有者'（原判定允许 'L4+持有者' 这种无空格写法）。 */
    private static final Pattern LEGACY_GATE = Pattern.compile("^L([2-6])\\+\\s*持有者$");
    private static final Map<String, String> LABELS = Map.of(
            ALL, "全部用户",
            L2_PLUS, "L2+ 持有者",
            L3_PLUS, "L3+ 持有者",
            L4_PLUS, "L4+ 持有者",
            L5_PLUS, "L5+ 持有者",
            L6_PLUS, "L6+ 持有者");

    private TradeinEligibility() {
    }

    /** 稳定代码；空或未知返回 null。同时接受历史中文值。 */
    public static String codeOf(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (CODE.matcher(value).matches()) return value;
        if ("全部用户".equals(value)) return ALL;
        Matcher gate = LEGACY_GATE.matcher(value);
        return gate.matches() ? "L" + gate.group(1) + "_PLUS" : null;
    }

    /** 运营后台(PC)既有的中文档位名；未知值原样返回，便于暴露问题。 */
    public static String labelOf(String raw) {
        String code = codeOf(raw);
        return code == null ? (raw == null ? "" : raw.trim()) : LABELS.get(code);
    }

    /** 该档要求的最低 V 等级；ALL 为 1。代码未知返回 null。 */
    public static Integer minLevelOf(String raw) {
        String code = codeOf(raw);
        if (code == null) return null;
        return ALL.equals(code) ? 1 : Integer.parseInt(code.substring(1, 2));
    }
}
