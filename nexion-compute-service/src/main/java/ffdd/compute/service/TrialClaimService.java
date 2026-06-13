package ffdd.compute.service;

import ffdd.compute.dto.TrialClaimResponse;

public interface TrialClaimService {
    TrialClaimResponse current(Long userId);

    TrialClaimResponse claim(Long userId, String clientRequestNo);
}
