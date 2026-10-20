package ffdd.opsconsole.shared.security;

import static org.mockito.Mockito.*;

import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import org.junit.jupiter.api.Test;

class AppSessionExpirationTest {
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final PhoneExecutionSessionContract phone = mock(PhoneExecutionSessionContract.class);
    private final UserSessionRevocationService revocations = new UserSessionRevocationService(sessions, phone);

    @Test void currentAppExpiryStopsPhoneAndRecordsExpirationAfterCheckingOwnership() {
        target("APP");
        when(sessions.countFixedContext("sid", 42L)).thenReturn(1);
        revocations.expireOwned(42L, "sid");
        var ordered = inOrder(sessions, phone);
        ordered.verify(sessions).lockSecurityAccount(42L);
        ordered.verify(sessions).findSession("sid");
        ordered.verify(sessions).countFixedContext("sid", 42L);
        ordered.verify(sessions).revokeOwnedUserSession(42L, "sid");
        ordered.verify(sessions).endChain("chain", "SESSION_EXPIRED");
        ordered.verify(phone).endedApp(42L);
    }

    @Test void supersededAppExpiryCannotStopCurrentInstallation() {
        target("APP");
        revocations.expireOwned(42L, "sid");
        verify(sessions).endChain("chain", "SESSION_EXPIRED");
        verifyNoInteractions(phone);
    }

    @Test void h5ExpiryRecordsOrdinaryExpiryWithoutPhoneSideEffect() {
        target("H5");
        revocations.expireOwned(42L, "sid");
        verify(sessions).endChain("chain", "SESSION_EXPIRED");
        verifyNoInteractions(phone);
    }

    @Test void explicitSecurityRevocationRetainsItsOwnReason() {
        target("APP");
        revocations.revokeOwned(42L, "sid");
        verify(sessions).endChain("chain", "SECURITY_REVOKED");
    }

    private void target(String group) {
        var target = new UserSessionEntity(); target.setUserId(42L);
        target.setSessionGroup(group); target.setSessionChainId("chain");
        when(sessions.findSession("sid")).thenReturn(target);
    }
}
