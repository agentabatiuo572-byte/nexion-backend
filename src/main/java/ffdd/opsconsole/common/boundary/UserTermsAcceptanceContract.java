package ffdd.opsconsole.common.boundary;

import ffdd.opsconsole.shared.api.ApiResult;

/** Existing published-terms gate, without requiring onboarding to already be complete. */
public interface UserTermsAcceptanceContract extends DomainFacade {
    ApiResult<Void> checkTerms(Long userId);
}
