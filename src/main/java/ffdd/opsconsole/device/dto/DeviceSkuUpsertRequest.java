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
        String operator) {
}
