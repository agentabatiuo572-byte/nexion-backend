package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserKycOverview(
        UserKycStats stats,
        String networkWhitelist,
        List<UserKycLedgerRow> rows,
        List<String> sources,
        List<String> redlines) {
}
