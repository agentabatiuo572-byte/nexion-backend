package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReferralRewardEffectiveAtContractTest {

    @Test
    void pendingSettlementAndAppPendingCountShareTheInclusiveEffectiveBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));

        int appPendingMethod = source.indexOf("long appPendingCount");
        String appPending = source.substring(source.lastIndexOf("@Select", appPendingMethod), appPendingMethod);
        String settlementPending = between(source, "SELECT u.id AS invitedUserId", "List<ReferralRow> findPendingReferrals");

        assertThat(appPending).contains("invited.created_at >= #{effectiveAt}");
        assertThat(settlementPending).contains("u.created_at >= #{effectiveAt}");
    }

    @Test
    void historicalSettlementCountsAndRecentLedgerRemainReadableAcrossAReEnableBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/ReferralRewardMapper.java"));

        String historical = between(source, "long appPositiveSettlementCount", "long appSandboxInvitedCount");

        assertThat(historical)
                .contains("appSettlementCount", "appVerifiedRewardSummary", "appRecentVerifiedRewards")
                .doesNotContain("#{effectiveAt}");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertThat(from).as("start marker %s", start).isGreaterThanOrEqualTo(0);
        assertThat(to).as("end marker %s", end).isGreaterThan(from);
        return source.substring(from, to);
    }
}
