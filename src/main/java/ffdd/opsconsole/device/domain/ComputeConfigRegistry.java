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

    public static String flagKey(String flag) { return PARAM_PREFIX + flag; }
    public static String coeffKey(String key) { return PARAM_PREFIX + key; }
    public static String yieldKey(String key) { return PARAM_PREFIX + "yieldEstimate." + key; }
    public static String gpuTierKey(String id, String field) { return PARAM_PREFIX + "gpuTier." + id + "." + field; }
    public static String downloadKey(String field) { return PARAM_PREFIX + "download." + field; }

    public record FlagDef(String key, String label, String desc, boolean defaultOn, String frontendEffect) {}
    public static final List<FlagDef> FLAGS = List.of(
        new FlagDef("computeShareEnabled", "电脑共享算力入口",
            "控制客户端『电脑共享算力』PC 弱入口与下载页的显隐。", false,
            "开启后客户端显现电脑算力 PC 入口与下载页;关闭则隐藏。"));

    public record CoeffDef(String key, String label, String defaultVal, String unit, String desc, String frontendEffect) {}
    public static final List<CoeffDef> COEFFICIENTS = List.of(
        new CoeffDef("h5BaseFactor", "H5 基础托管系数", "0.6", "× 基线 · 取值 >0 且 ≤1",
            "H5(网页非常驻载体)按基准产出 × 此系数计算基础托管产出。", "调高=H5 基础托管产出更高;调低=放大 App 在线加成优势。"),
        new CoeffDef("continuityFullHours", "连续在线满额时长", "2", "小时",
            "App 连续在线达此时长后稳定性加成升至满额 1.0。", "调短=更快满额;调长=需更久连续在线。"));

    public record YieldDef(String key, String label, String defaultVal, String unit) {}
    public static final List<YieldDef> YIELD_ESTIMATE = List.of(
        new YieldDef("topsBaseline", "收益估算基准算力", "28", "TOPS"),
        new YieldDef("dailyUsdtPerBaseline", "基准日产 USDT", "0.06", "USDT / 日"),
        new YieldDef("nexPerUsdt", "NEX 折算系数", "166.67", "NEX / USDT"));

    public record GpuTierDef(String id, String label, String desc, String defaultModel, String defaultTops, List<String> keywords) {}
    public static final List<String> GPU_TIER_IDS = List.of("G1", "G2", "G3", "G4", "G5", "G6");
    public static final List<String> KEYWORD_SLOTS = List.of("keyword1", "keyword2", "keyword3", "keyword4", "keyword5", "keyword6");
    public static final List<GpuTierDef> GPU_TIERS = List.of(
        new GpuTierDef("G1", "入门显卡", "集显或旧独显,只放轻量任务。", "Intel Iris Xe", "40", List.of("gtx 1650", "gtx 1060", "gtx 1050", "integrated", "iris xe", "vega")),
        new GpuTierDef("G2", "标准显卡", "主流入门独显,也是未知型号的保守兜底档。", "NVIDIA RTX 3060", "90", List.of("rtx 3060", "rtx 3050", "rtx 2060", "gtx 1080", "rx 6600")),
        new GpuTierDef("G3", "进阶显卡", "中端独显,可承接更长的推理任务。", "NVIDIA RTX 4060", "160", List.of("rtx 4060 ti", "rtx 4060", "rtx 3070", "rx 7600", "rx 6700")),
        new GpuTierDef("G4", "高阶显卡", "高性能个人显卡,默认演示型号落在此档。", "NVIDIA RTX 4070", "290", List.of("rtx 4070 ti", "rtx 4070", "rtx 3080", "rx 7800")),
        new GpuTierDef("G5", "超高阶显卡", "高端工作站或旗舰上一代独显。", "NVIDIA RTX 4080", "460", List.of("rtx 5080", "rtx 4080", "rx 7900", "a5000")),
        new GpuTierDef("G6", "旗舰显卡", "旗舰显卡或数据中心卡,仅承接高收益任务。", "NVIDIA RTX 4090", "660", List.of("rtx 5090", "rtx 4090", "h100", "a100", "l40")));

    public record DownloadFieldDef(String field, String label, String defaultVal) {}
    public static final List<DownloadFieldDef> DOWNLOAD_FIELDS = List.of(
        new DownloadFieldDef("url", "客户端下载地址", ""),
        new DownloadFieldDef("zhTitle", "中文标题", "电脑显卡算力共享"),
        new DownloadFieldDef("zhGuide", "中文说明", "下载桌面客户端,使用同一账号登录,连接后电脑会出现在设备仓库中。"),
        new DownloadFieldDef("enTitle", "英文标题", "Computer GPU share"),
        new DownloadFieldDef("enGuide", "英文说明", "Download the desktop client, sign in with the same account, and the computer appears in device inventory after connection."));

    private static final Set<String> EXACT_PARAM_KEYS = exactParamKeys();

    /** PATCH 白名单必须精确到注册字段，不能只按前缀放行未知档位或未知字段。 */
    public static boolean isComputeParamKey(String key) {
        return key != null && EXACT_PARAM_KEYS.contains(key);
    }

    public static boolean isFlagParamKey(String key) {
        return flagKey("computeShareEnabled").equals(key);
    }

    public static boolean isCoefficientParamKey(String key) {
        return coeffKey("h5BaseFactor").equals(key) || coeffKey("continuityFullHours").equals(key);
    }

    private static Set<String> exactParamKeys() {
        Set<String> keys = new LinkedHashSet<>();
        FLAGS.forEach(flag -> keys.add(flagKey(flag.key())));
        COEFFICIENTS.forEach(coeff -> keys.add(coeffKey(coeff.key())));
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
