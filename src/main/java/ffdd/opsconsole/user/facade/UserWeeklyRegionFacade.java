package ffdd.opsconsole.user.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;

/** Registration calling-code authority, without exposing the account's phone number. */
public interface UserWeeklyRegionFacade extends DomainFacade {
    record RegisteredCallingCode(Long userId, String countryCode, boolean sandbox) {}

    /** Null means no active account in this exact environment; absence does not mark a caller transaction rollback-only. */
    RegisteredCallingCode lockActiveRegisteredCallingCode(Long userId, boolean expectedSandbox);
}
