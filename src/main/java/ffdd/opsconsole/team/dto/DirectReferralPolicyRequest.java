package ffdd.opsconsole.team.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.team.domain.DirectReferralPolicy.Rule;

public record DirectReferralPolicyRequest(Long expectedVersion, Rule purchase,
                                          Rule deviceEarning, String reason) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("DIRECT_REFERRAL_POLICY_FIELD_INVALID: " + field);
    }
}
