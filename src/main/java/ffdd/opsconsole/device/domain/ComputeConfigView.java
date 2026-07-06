package ffdd.opsconsole.device.domain;
import java.util.List;
public record ComputeConfigView(
        String domain,
        List<FlagView> flags,
        List<CoeffView> coefficients,
        List<YieldView> yieldEstimate,
        List<GpuTierView> gpuTiers,
        DownloadView download,
        List<String> sources) {
    public record FlagView(String key, String label, String desc, boolean enabled, String frontendEffect) {}
    public record CoeffView(String key, String label, String value, String unit, String desc, String frontendEffect) {}
    public record YieldView(String key, String label, String value, String unit) {}
    public record GpuTierView(String id, String label, String desc, String defaultModel, String tops, List<String> keywords) {}
    public record DownloadView(String url, String zhTitle, String zhGuide, String enTitle, String enGuide) {}
}
