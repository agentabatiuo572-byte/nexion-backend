package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserAccountView(
        Long id,
        String userNo,
        String nickname,
        String phoneMasked,
        String countryCode,
        String status,
        String kycStatus,
        String userLevel,
        String vRank,
        Boolean twoFactorEnabled,
        BigDecimal walletUsdt,
        BigDecimal walletNex,
        Integer riskScore,
        String riskBand,
        Long deviceCount,
        Long activeDeviceCount,
        LocalDateTime registeredAt,
        LocalDateTime lastLoginAt) {
}
