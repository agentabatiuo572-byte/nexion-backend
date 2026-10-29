package ffdd.opsconsole.content.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Installation;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.Notification;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class NativePushServiceTest {
    final NativePushMapper mapper = mock(NativePushMapper.class);
    final CurrentAppSessionService sessions = mock(CurrentAppSessionService.class);
    final NativePushService service = new NativePushService(mapper, sessions);
    final Authentication auth = mock(Authentication.class);
    static final String ID = "installation-abc";
    static final String TOKEN = "a".repeat(64);

    UserSessionEntity session(long user, String chain) {
        var s = new UserSessionEntity(); s.setUserId(user); s.setSessionChainId(chain); s.setInstallationId(ID); return s;
    }
    Installation binding(long user, String chain) {
        return new Installation(ID,"cid-123456",NativePushService.hashToken(TOKEN),user,chain,"android","granted","1",3,true);
    }
    NativePushService.Registration request() { return new NativePushService.Registration("cid-123456","android","granted","1"); }

    @Test void createsBindingFromAuthenticatedAppSessionAndHashesClientProof() {
        doReturn(session(7,"chain")).when(sessions).requireCurrent(auth,ID,false);
        service.register(auth,ID,TOKEN,request());
        verify(mapper).insert(argThat(i -> i.userId()==7 && i.sessionChainId().equals("chain")
                && i.bindingTokenHash().length()==64 && !i.bindingTokenHash().equals(TOKEN)));
    }
    @Test void retryDoesNotAdvanceEpochOrDropQueuedNotifications() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(7,"chain"));
        when(mapper.lockInstallation(ID)).thenReturn(binding(7,"chain"));
        service.register(auth,ID,TOKEN,request());
        verify(mapper).update(any(),eq(0)); verify(mapper,never()).cancelPending(any());
    }
    @Test void accountSwitchRequiresPossessionAndInvalidatesPendingEpoch() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(8,"new-chain"));
        when(mapper.lockInstallation(ID)).thenReturn(binding(7,"chain"));
        assertThatThrownBy(() -> service.register(auth,ID,"b".repeat(64),request())).isInstanceOf(BizException.class).hasMessage("PUSH_BINDING_PROOF_REQUIRED");
        verify(mapper,never()).update(any(),anyInt());
        service.register(auth,ID,TOKEN,request());
        verify(mapper).cancelPending(ID); verify(mapper).update(argThat(i -> i.userId()==8),eq(1));
    }
    @Test void cidCannotBeStolenByNewInstallationEvenWithOwnValidSession() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(8,"chain"));
        when(mapper.cidOwner("cid-123456")).thenReturn("another-installation");
        assertThatThrownBy(() -> service.register(auth,ID,TOKEN,request())).hasMessage("PUSH_CID_ALREADY_BOUND");
        verify(mapper,never()).insert(any());
    }
    @Test void mismatchedSessionIsRejectedBeforeMutationAndOldAccountCannotUnbindNewAccount() {
        when(sessions.requireCurrent(auth,ID,false)).thenThrow(new BizException(409,"INSTALLATION_MISMATCH"));
        assertThatThrownBy(() -> service.register(auth,ID,TOKEN,request())).hasMessage("INSTALLATION_MISMATCH");
        verifyNoInteractions(mapper);
        doReturn(session(7,"chain")).when(sessions).requireCurrent(auth,ID,false);
        when(mapper.lockInstallation(ID)).thenReturn(binding(8,"new"));
        assertThatThrownBy(() -> service.remove(auth,ID,TOKEN)).hasMessage("PUSH_BINDING_NOT_OWNED");
        verify(mapper,never()).deactivate(any());
    }
    @Test void notificationLookupChecksCurrentUserAndTargetOwnership() {
        when(sessions.requireSession(auth)).thenReturn(session(7,"chain"));
        assertThatThrownBy(() -> service.resolve(auth,44)).hasMessage("PUSH_NOTIFICATION_NOT_FOUND");
        when(mapper.ownedNotification(44,7)).thenReturn(new Notification(44,"DEVICE_ALERT","/pages/earn/device-detail?id=18","en"));
        assertThatThrownBy(() -> service.resolve(auth,44)).hasMessage("PUSH_TARGET_NOT_FOUND");
        when(mapper.ownedDevice(18,7)).thenReturn(1);
        assertThat(service.resolve(auth,44).route()).isEqualTo("/pages/earn/device-detail?id=18");
    }
    @Test void routesAreFixedAndNeverAcceptExternalUrlOrExtraQuery() {
        assertThatThrownBy(() -> NativePushService.route(new Notification(1,"FUNDS_RESULT","https://evil.example","en"))).hasMessage("PUSH_ROUTE_UNAVAILABLE");
        assertThatThrownBy(() -> NativePushService.route(new Notification(1,"DEVICE_ALERT","/pages/earn/device-detail?id=1&url=x","en"))).hasMessage("PUSH_ROUTE_UNAVAILABLE");
        assertThat(NativePushService.route(new Notification(1,"DEVICE_EXPIRY","/pages/me/trial","en")).route()).isEqualTo("/pages/me/trial");
    }
    @Test void openedReceiptRequiresCurrentBindingAndNeverClaimsDeviceDelivery() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(7,"chain"));
        when(mapper.ownedNotification(44,7)).thenReturn(new Notification(44,"FUNDS_RESULT","/pages/me/wallet-bills","en"));
        when(mapper.lockInstallation(ID)).thenReturn(binding(8,"new"));
        assertThatThrownBy(() -> service.opened(auth,44,ID)).hasMessage("PUSH_BINDING_NOT_OWNED");
        verify(mapper,never()).opened(anyLong(),any());
        when(mapper.lockInstallation(ID)).thenReturn(binding(7,"chain"));
        service.opened(auth,44,ID); verify(mapper).opened(44,binding(7,"chain"));
    }
}
