package ffdd.opsconsole.user.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;

/** The existing registration writes, restricted to their owning user domain. */
public interface UserRegistrationWriteFacade extends DomainFacade {
    long insertNewAccount(NewAccountCommand command);
    void ensureRegisteredWallet(long customerId, int sandbox, String expectedIncarnation);

    record NewAccountCommand(String countryCode, String phone, String clientIp, String passwordHash,
        String nickname, String referralCode, Long sponsorUserId, String sponsorCode,
        String userLevel, String vRank, String status, int sandbox, String language) {}
}
