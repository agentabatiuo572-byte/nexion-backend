package ffdd.opsconsole.user.domain;

import java.time.LocalDateTime;

/** Authoritative C4 projection backed by nx_kyc_profile. */
public record UserKycRecord(
        Long userId,
        String userNo,
        String nickname,
        String phoneMasked,
        String countryCode,
        String accountStatus,
        String userLevel,
        String status,
        String pairedAddress,
        String network,
        LocalDateTime pairedAt,
        String triggerSource,
        Long version) {
}
