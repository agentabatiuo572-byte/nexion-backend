package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserKycLedgerRow(
        Long userId,
        String displayId,
        String nickname,
        String phoneMasked,
        String countryCode,
        String status,
        String backendStatus,
        String statusLabel,
        String statusTone,
        String pairedAddressMasked,
        String network,
        String pairedAt,
        String triggerSource,
        List<UserKycKeyValue> info,
        List<String> history) {
}
