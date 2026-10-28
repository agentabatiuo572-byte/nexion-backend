package ffdd.opsconsole.device.domain;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** E6 算力与设备配置寄存器 —— 元数据 + 默认值 + paramKey 命名 + 字段校验白名单。
 *  移植自高保真 lib/mock/admin/compute-config.ts 的 COMPUTE_*，与前端 pget key 1:1。 */
public final class ComputeConfigRegistry {
    private ComputeConfigRegistry() {}

    public static final String PARAM_PREFIX = "E.compute.";
    public static final String CONFIG_GROUP = "e6_compute";

    public static String phoneBindingKey(String field) { return PARAM_PREFIX + "phoneBinding." + field; }

    public static String flagKey(String flag) { return PARAM_PREFIX + flag; }
    public static String coeffKey(String key) { return PARAM_PREFIX + key; }
    public static String yieldKey(String key) { return PARAM_PREFIX + "yieldEstimate." + key; }
    public static String gpuTierKey(String id, String field) { return PARAM_PREFIX + "gpuTier." + id + "." + field; }
    public static String downloadKey(String field) { return PARAM_PREFIX + "download." + field; }

    public record FlagDef(String key, String label, String desc, boolean defaultOn, String frontendEffect) {}
    public static final List<FlagDef> FLAGS = List.of();

    public record CoeffDef(String key, String label, String defaultVal, String unit, String desc, String frontendEffect) {}
    public static final List<CoeffDef> COEFFICIENTS = List.of(
        new CoeffDef("h5BaseFactor", "H5 基础托管系数（已退役）", "0", "只读兼容值",
            "手机任务不在 H5 执行，此字段固定为零。", "不可修改。"),
        new CoeffDef("continuityFullHours", "连续在线满额时长", "2", "小时",
            "App 连续在线达此时长后稳定性加成升至满额 1.0。", "调短=更快满额;调长=需更久连续在线。"));

    public record YieldDef(String key, String label, String defaultVal, String unit) {}
    public static final List<YieldDef> YIELD_ESTIMATE = List.of(
        new YieldDef("topsBaseline", "收益估算基准算力", "28", "TOPS"),
        new YieldDef("dailyUsdtPerBaseline", "基准日产 USDT", "0.06", "USDT / 日"),
        new YieldDef("nexPerUsdt", "UVT 折算系数", "166.67", "UVT / USDT"));

    public record GpuTierDef(String id, String label, String desc, String defaultModel, String defaultTops, List<String> keywords) {}
    public static final List<String> GPU_TIER_IDS = List.of();
    public static final List<String> KEYWORD_SLOTS = List.of("keyword1", "keyword2", "keyword3", "keyword4", "keyword5", "keyword6");
    public static final List<GpuTierDef> GPU_TIERS = List.of();
    // Fixed, read-only metadata for installed clients that require all six historical tiers.
    // These are not editable parameters and never authorize device enrollment.
    public static final List<GpuTierDef> LEGACY_WIRE_GPU_TIERS = List.of(
        new GpuTierDef("G1", "入门显卡", "集显或旧独显,只放轻量任务。", "Intel Iris Xe", "40", List.of("gtx 1650", "gtx 1060", "gtx 1050", "integrated", "iris xe", "vega")),
        new GpuTierDef("G2", "标准显卡", "主流入门独显，识别不出型号时也归到这一档。", "NVIDIA RTX 3060", "90", List.of("rtx 3060", "rtx 3050", "rtx 2060", "gtx 1080", "rx 6600")),
        new GpuTierDef("G3", "进阶显卡", "中端独显,可承接更长的推理任务。", "NVIDIA RTX 4060", "160", List.of("rtx 4060 ti", "rtx 4060", "rtx 3070", "rx 7600", "rx 6700")),
        new GpuTierDef("G4", "高阶显卡", "高性能个人显卡。", "NVIDIA RTX 4070", "290", List.of("rtx 4070 ti", "rtx 4070", "rtx 3080", "rx 7800")),
        new GpuTierDef("G5", "超高阶显卡", "高端工作站或旗舰上一代独显。", "NVIDIA RTX 4080", "460", List.of("rtx 5080", "rtx 4080", "rx 7900", "a5000")),
        new GpuTierDef("G6", "旗舰显卡", "旗舰显卡或数据中心卡,仅承接高收益任务。", "NVIDIA RTX 4090", "660", List.of("rtx 5090", "rtx 4090", "h100", "a100", "l40")));


    public record DownloadFieldDef(String field, String label, String defaultVal) {}
    public static final List<DownloadFieldDef> DOWNLOAD_FIELDS = List.of();

    private static final List<String> ALL_PARAM_KEYS = List.copyOf(exactParamKeys());
    private static final Set<String> EXACT_PARAM_KEYS = Set.copyOf(ALL_PARAM_KEYS);

    /** PATCH 白名单必须精确到注册字段，不能只按前缀放行未知档位或未知字段。 */
    public static boolean isComputeParamKey(String key) {
        return key != null && EXACT_PARAM_KEYS.contains(key);
    }

    public static boolean isFlagParamKey(String key) {
        return phoneBindingKey("allowReplacement").equals(key);
    }

    public static boolean isCoefficientParamKey(String key) {
        return coeffKey("h5BaseFactor").equals(key) || coeffKey("continuityFullHours").equals(key);
    }

    /** 稳定顺序用于事务内统一加锁，避免单参数与批量参数并发时出现交叉死锁。 */
    public static List<String> allParamKeys() {
        return ALL_PARAM_KEYS;
    }

    /** 缺失或非法存量值的唯一默认来源，写前全局不变量校验也必须使用同一组默认值。 */
    public static String defaultValue(String key) {
        if (phoneBindingKey("allowReplacement").equals(key)) return "off";
        if (phoneBindingKey("minReplacementIntervalDays").equals(key)) return "1";
        if (phoneBindingKey("replacementWindowDays").equals(key)) return "30";
        if (phoneBindingKey("maxReplacementsInWindow").equals(key)) return "2";
        for (FlagDef flag : FLAGS) {
            if (flagKey(flag.key()).equals(key)) {
                return flag.defaultOn() ? "on" : "off";
            }
        }
        for (CoeffDef coeff : COEFFICIENTS) {
            if (coeffKey(coeff.key()).equals(key)) {
                return coeff.defaultVal();
            }
        }
        for (YieldDef item : YIELD_ESTIMATE) {
            if (yieldKey(item.key()).equals(key)) {
                return item.defaultVal();
            }
        }
        for (GpuTierDef tier : GPU_TIERS) {
            if (gpuTierKey(tier.id(), "label").equals(key)) {
                return tier.label();
            }
            if (gpuTierKey(tier.id(), "tops").equals(key)) {
                return tier.defaultTops();
            }
            for (int index = 0; index < KEYWORD_SLOTS.size(); index++) {
                if (gpuTierKey(tier.id(), KEYWORD_SLOTS.get(index)).equals(key)) {
                    return index < tier.keywords().size() ? tier.keywords().get(index) : "";
                }
            }
        }
        for (DownloadFieldDef field : DOWNLOAD_FIELDS) {
            if (downloadKey(field.field()).equals(key)) {
                return field.defaultVal();
            }
        }
        throw new IllegalArgumentException("Unknown E6 compute config key: " + key);
    }

    private static Set<String> exactParamKeys() {
        Set<String> keys = new LinkedHashSet<>();
        FLAGS.forEach(flag -> keys.add(flagKey(flag.key())));
        COEFFICIENTS.stream().filter(coeff -> !"h5BaseFactor".equals(coeff.key()))
                .forEach(coeff -> keys.add(coeffKey(coeff.key())));
        keys.add(phoneBindingKey("allowReplacement"));
        keys.add(phoneBindingKey("minReplacementIntervalDays"));
        keys.add(phoneBindingKey("replacementWindowDays"));
        keys.add(phoneBindingKey("maxReplacementsInWindow"));
        YIELD_ESTIMATE.forEach(item -> keys.add(yieldKey(item.key())));
        for (String tierId : GPU_TIER_IDS) {
            keys.add(gpuTierKey(tierId, "label"));
            keys.add(gpuTierKey(tierId, "tops"));
            KEYWORD_SLOTS.forEach(slot -> keys.add(gpuTierKey(tierId, slot)));
        }
        DOWNLOAD_FIELDS.forEach(field -> keys.add(downloadKey(field.field())));
        return Set.copyOf(keys);
    }
}
