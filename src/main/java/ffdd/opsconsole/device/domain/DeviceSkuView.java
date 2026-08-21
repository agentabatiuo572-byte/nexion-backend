package ffdd.opsconsole.device.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record DeviceSkuView(
        String skuId,
        String name,
        String tier,
        String tagline,
        String badge,
        String gpu,
        String vram,
        String hashRate,
        String power,
        String datacenter,
        String uptime,
        String warranty,
        BigDecimal phoneDailyEarn,
        BigDecimal phoneDailyEarnNex,
        BigDecimal price,
        BigDecimal dailyEarn,
        BigDecimal dailyEarnNex,
        BigDecimal shareYieldMin,
        BigDecimal shareYieldMax,
        String baseRate,
        Long sold,
        String stock,
        BigDecimal rating,
        Long reviews,
        Long aiImageGenPerMin,
        Long aiLlmTokensPerSec,
        Long aiVideoMinPerHour,
        Long aiFineTuneMins,
        String aiUnlocks,
        List<String> features,
        Integer generation,
        String lifecycle,
        String supersededBy,
        BigDecimal tradeinDiscount,
        String unlockPhase,
        DevicePurchaseGateView purchaseGate,
        String imageAssetId,
        String imageObjectKey,
        String imagePreviewUrl,
        String tag,
        String status,
        LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS") LocalDateTime updatedAt) {

    /** Compatibility constructor for pre-P2 callers that did not project product specs. */
    public DeviceSkuView(String skuId, String name, String tier, String tagline, String badge, String gpu,
                         String vram, String hashRate, String power, String datacenter, BigDecimal price,
                         BigDecimal dailyEarn, BigDecimal dailyEarnNex, BigDecimal shareYieldMin,
                         BigDecimal shareYieldMax, String baseRate, Long sold, String stock, BigDecimal rating,
                         Long reviews, Long aiImageGenPerMin, Long aiLlmTokensPerSec, Long aiVideoMinPerHour,
                         Long aiFineTuneMins, String aiUnlocks, List<String> features, Integer generation,
                         String lifecycle, String supersededBy, BigDecimal tradeinDiscount, String unlockPhase,
                         DevicePurchaseGateView purchaseGate, String imageAssetId, String imageObjectKey,
                         String imagePreviewUrl, String tag, String status, LocalDateTime createdAt,
                         LocalDateTime updatedAt) {
        this(skuId, name, tier, tagline, badge, gpu, vram, hashRate, power, datacenter, null, null, null, null,
                price, dailyEarn, dailyEarnNex, shareYieldMin, shareYieldMax, baseRate, sold, stock, rating,
                reviews, aiImageGenPerMin, aiLlmTokensPerSec, aiVideoMinPerHour, aiFineTuneMins, aiUnlocks,
                features, generation, lifecycle, supersededBy, tradeinDiscount, unlockPhase, purchaseGate,
                imageAssetId, imageObjectKey, imagePreviewUrl, tag, status, createdAt, updatedAt);
    }
}
