package ffdd.opsconsole.user.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/** C1 list projection that deliberately excludes the internal numeric database id. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileListView(
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

    public static UserProfileListView from(UserAccountView value, String roleCode) {
        String role = roleCode == null ? "" : roleCode.trim().toUpperCase(Locale.ROOT);
        boolean full = Set.of("SUPER_ADMIN", "RISK", "AUDITOR").contains(role);
        boolean canFinance = full || Set.of("FINANCE", "SUPPORT").contains(role);
        boolean canSeeRiskBand = full || "SUPPORT".equals(role);
        boolean canSeeDevices = full || "SUPPORT".equals(role);
        return new UserProfileListView(
                value.userNo(), value.nickname(), value.phoneMasked(), value.countryCode(),
                value.status(), value.kycStatus(), value.userLevel(), value.vRank(),
                full ? value.twoFactorEnabled() : null,
                canFinance ? value.walletUsdt() : null,
                canFinance ? value.walletNex() : null,
                full ? value.riskScore() : null,
                canSeeRiskBand ? value.riskBand() : null,
                canSeeDevices ? value.deviceCount() : null,
                canSeeDevices ? value.activeDeviceCount() : null,
                value.registeredAt(), value.lastLoginAt());
    }
}
