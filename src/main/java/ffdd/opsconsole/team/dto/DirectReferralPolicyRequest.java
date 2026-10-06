package ffdd.opsconsole.team.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.team.domain.DirectReferralPolicy.Rule;

public record DirectReferralPolicyRequest(Integer schemaVersion, Long expectedVersion, Long expectedSevenLayerRevision,
                                          PurchaseSplit purchaseSplit, Rule purchase, Rule deviceEarning, String reason) {
    public record PurchaseSplit(Boolean enabled, java.math.BigDecimal usdtSharePct) {
        @JsonAnySetter public void rejectUnknownField(String field,Object value) { throw new IllegalArgumentException("DIRECT_REFERRAL_POLICY_FIELD_INVALID: " + field); }
    }
    public DirectReferralPolicyRequest(Long expectedVersion, Rule purchase, Rule deviceEarning, String reason) {
        this(1,expectedVersion,null,null,purchase,deviceEarning,reason);
    }
    public Rule effectivePurchase() {
        return Integer.valueOf(2).equals(schemaVersion) && purchaseSplit != null
                ? new Rule(purchaseSplit.enabled(),java.math.BigDecimal.TEN,purchaseSplit.usdtSharePct(),0) : purchase;
    }
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("DIRECT_REFERRAL_POLICY_FIELD_INVALID: " + field);
    }
}
