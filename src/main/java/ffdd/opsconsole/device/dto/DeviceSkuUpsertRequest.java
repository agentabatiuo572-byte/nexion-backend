package ffdd.opsconsole.device.dto;

import ffdd.opsconsole.device.domain.DevicePurchaseGateView;
import java.math.BigDecimal;
import java.util.List;

public record DeviceSkuUpsertRequest(
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
        String reason,
        String operator,
        String inventoryMode,
        Boolean trialEligible) {

    /** Compatibility constructor for callers before explicit inventory semantics. */
    public DeviceSkuUpsertRequest(
            String skuId, String name, String tier, String tagline, String badge, String gpu, String vram,
            String hashRate, String power, String datacenter, String uptime, String warranty,
            BigDecimal phoneDailyEarn, BigDecimal phoneDailyEarnNex, BigDecimal price, BigDecimal dailyEarn,
            BigDecimal dailyEarnNex, BigDecimal shareYieldMin, BigDecimal shareYieldMax, String baseRate,
            Long sold, String stock, BigDecimal rating, Long reviews, Long aiImageGenPerMin,
            Long aiLlmTokensPerSec, Long aiVideoMinPerHour, Long aiFineTuneMins, String aiUnlocks,
            List<String> features, Integer generation, String lifecycle, String supersededBy,
            BigDecimal tradeinDiscount, String unlockPhase, DevicePurchaseGateView purchaseGate,
            String imageAssetId, String imageObjectKey, String imagePreviewUrl, String tag, String status,
            String reason, String operator) {
        this(skuId, name, tier, tagline, badge, gpu, vram, hashRate, power, datacenter, uptime, warranty,
                phoneDailyEarn, phoneDailyEarnNex, price, dailyEarn, dailyEarnNex, shareYieldMin,
                shareYieldMax, baseRate, sold, stock, rating, reviews, aiImageGenPerMin, aiLlmTokensPerSec,
                aiVideoMinPerHour, aiFineTuneMins, aiUnlocks, features, generation, lifecycle, supersededBy,
                tradeinDiscount, unlockPhase, purchaseGate, imageAssetId, imageObjectKey, imagePreviewUrl,
                tag, status, reason, operator, null, null);
    }

    /** Compatibility constructor for existing E1 replay/tests before P2 specs. */
    public DeviceSkuUpsertRequest(String skuId, String name, String tier, String tagline, String badge, String gpu,
                                  String vram, String hashRate, String power, String datacenter, BigDecimal price,
                                  BigDecimal dailyEarn, BigDecimal dailyEarnNex, BigDecimal shareYieldMin,
                                  BigDecimal shareYieldMax, String baseRate, Long sold, String stock,
                                  BigDecimal rating, Long reviews, Long aiImageGenPerMin, Long aiLlmTokensPerSec,
                                  Long aiVideoMinPerHour, Long aiFineTuneMins, String aiUnlocks, List<String> features,
                                  Integer generation, String lifecycle, String supersededBy, BigDecimal tradeinDiscount,
                                  String unlockPhase, DevicePurchaseGateView purchaseGate, String imageAssetId,
                                  String imageObjectKey, String imagePreviewUrl, String tag, String status,
                                  String reason, String operator) {
        this(skuId, name, tier, tagline, badge, gpu, vram, hashRate, power, datacenter, null, null, null, null,
                price, dailyEarn, dailyEarnNex, shareYieldMin, shareYieldMax, baseRate, sold, stock, rating,
                reviews, aiImageGenPerMin, aiLlmTokensPerSec, aiVideoMinPerHour, aiFineTuneMins, aiUnlocks,
                features, generation, lifecycle, supersededBy, tradeinDiscount, unlockPhase, purchaseGate,
                imageAssetId, imageObjectKey, imagePreviewUrl, tag, status, reason, operator, null, null);
    }
}
