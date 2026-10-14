package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
public class UserRegistrationWriteAdapter implements UserRegistrationWriteFacade {
    private final UserOpsMapper mapper;
    public UserRegistrationWriteAdapter(UserOpsMapper mapper) { this.mapper = mapper; }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public long insertNewAccount(NewAccountCommand c) {
        if (c == null || c.sandbox() < 0 || c.sandbox() > 1) throw new IllegalArgumentException("Invalid new account");
        // No ID exists in this command: callers cannot turn an existing account into a birth.
        UserEntity user = new UserEntity();
        user.setCountryCode(c.countryCode()); user.setPhone(c.phone()); user.setClientIp(c.clientIp());
        user.setPasswordHash(c.passwordHash()); user.setNickname(c.nickname()); user.setReferralCode(c.referralCode());
        user.setSponsorUserId(c.sponsorUserId()); user.setSponsorCode(c.sponsorCode());
        user.setUserLevel(c.userLevel()); user.setVRank(c.vRank()); user.setStatus(c.status());
        user.setSandbox(c.sandbox()); user.setLanguage(c.language()); user.setIsDeleted(0);
        if (mapper.insert(user) != 1 || user.getId() == null || user.getId() <= 0)
            throw new IllegalStateException("USER_REGISTRATION_INSERT_FAILED");
        return user.getId();
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void ensureRegisteredWallet(long customerId, int sandbox, String expectedIncarnation) {
        if (customerId <= 0 || sandbox < 0 || sandbox > 1) throw new IllegalArgumentException("Invalid registration wallet");
        if (sandbox == 1) {
            if (expectedIncarnation != null) throw new IllegalArgumentException("Excluded wallet has no fund birth nonce");
            mapper.ensureRegisteredUserWallet(customerId, sandbox);
        } else {
            if (expectedIncarnation == null || !expectedIncarnation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("Production registration requires its wallet nonce");
            // Affected rows are deliberately not used as an INSERT proof (CLIENT_FOUND_ROWS).
            mapper.ensureNewRegisteredUserWallet(customerId, sandbox, expectedIncarnation);
        }
    }
}
