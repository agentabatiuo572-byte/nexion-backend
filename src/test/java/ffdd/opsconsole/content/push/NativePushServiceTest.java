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
        return new Installation(ID,"fcm:"+"x".repeat(150),"FCM",NativePushService.hashToken(TOKEN),user,chain,"android","granted","1",3,true);
    }
    NativePushService.Registration request() { return new NativePushService.Registration("fcm:"+"x".repeat(150),"FCM","android","granted","1"); }

    @Test void purchasePushResolvesOnlyTheRecipientsOrder(){
        when(sessions.requireSession(auth)).thenReturn(session(7,"chain"));
        var notification=new Notification(42L,"FUNDS_RESULT","/pages/store/order-detail?id=ORD-1","en");
        when(mapper.ownedNotification(42,7)).thenReturn(notification);
        assertThatThrownBy(()->service.resolve(auth,42)).hasMessage("PUSH_TARGET_NOT_FOUND");
        when(mapper.ownedOrder("ORD-1",7)).thenReturn(1);
        assertThat(service.resolve(auth,42).route()).isEqualTo("/pages/store/order-detail?id=ORD-1");
        assertThatThrownBy(()->NativePushService.route(new Notification(43L,"FUNDS_RESULT","/pages/store/order-detail?id=ORD-1&user=8","en"))).hasMessage("PUSH_ROUTE_UNAVAILABLE");
        String longOrder="Z".repeat(96),route="/pages/store/order-detail?id="+longOrder;
        when(mapper.ownedNotification(44,7)).thenReturn(new Notification(44L,"FUNDS_RESULT",route,"en"));
        assertThatThrownBy(()->service.resolve(auth,44)).hasMessage("PUSH_TARGET_NOT_FOUND");
        when(mapper.ownedOrder(longOrder,7)).thenReturn(1);assertThat(service.resolve(auth,44).route()).isEqualTo(route);
    }
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
        when(mapper.tokenOwner("FCM","fcm:"+"x".repeat(150))).thenReturn("another-installation");
        assertThatThrownBy(() -> service.register(auth,ID,TOKEN,request())).hasMessage("PUSH_TOKEN_ALREADY_BOUND");
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
    @Test void providerPlatformAndTokenFormatRejectLegacyOrMalformedValues() {
        for (var request:java.util.List.of(
                new NativePushService.Registration("cid-123456",null,"android","granted","1"),
                new NativePushService.Registration("cid-123456","FCM","android","granted","1"),
                new NativePushService.Registration("a".repeat(64),"APNS","android","granted","1"),
                new NativePushService.Registration("a".repeat(63),"APNS","ios","granted","1"),
                new NativePushService.Registration("fcm:"+"x".repeat(4093),"FCM","android","granted","1")))
            assertThatThrownBy(() -> service.register(auth,ID,TOKEN,request)).hasMessage("INVALID_PUSH_REGISTRATION");
        verifyNoInteractions(mapper,sessions);
    }
    @Test void explicitProviderReplacementRequiresProofAdvancesEpochAndCanonicalizesApns() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(7,"chain"));
        when(mapper.lockInstallation(ID)).thenReturn(binding(7,"chain"));
        service.register(auth,ID,TOKEN,new NativePushService.Registration("AB".repeat(32),"APNS","ios","granted","2"));
        verify(mapper).update(argThat(i -> i.provider().equals("APNS") && i.clientId().equals("ab".repeat(32))),eq(1));
        verify(mapper).cancelPending(ID);
    }
    @Test void apnsAcceptsVariableNativeByteLengthAndRejectsMalformedTokens() {
        assertThat(NativePushService.validNativeToken("APNS","ios","ab".repeat(16))).isTrue();
        assertThat(NativePushService.validNativeToken("APNS","ios","AB".repeat(64))).isTrue();
        for (String token:java.util.List.of("","a".repeat(63),"ag".repeat(32),"a".repeat(4097)))
            assertThat(NativePushService.validNativeToken("APNS","ios",token)).isFalse();
    }
    @Test void uppercaseApnsAliasCannotTakeOverAnotherInstallationToken() {
        when(sessions.requireCurrent(auth,ID,false)).thenReturn(session(8,"other-chain"));
        when(mapper.tokenOwner("APNS","ab".repeat(16))).thenReturn("existing-installation");
        assertThatThrownBy(() -> service.register(auth,ID,TOKEN,
                new NativePushService.Registration("AB".repeat(16),"APNS","ios","granted","1")))
                .hasMessage("PUSH_TOKEN_ALREADY_BOUND");
        verify(mapper,never()).insert(any()); verify(mapper,never()).update(any(),anyInt());
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
    @Test void withdrawalPushResolvesOnlyTheExactOwnedOrder() {
        when(sessions.requireSession(auth)).thenReturn(session(7,"chain"));
        String route="/pages/me/wallet-withdraw-tracking?id=WD-ABC123";
        when(mapper.ownedNotification(44,7)).thenReturn(new Notification(44,"FUNDS_RESULT",route,"en"));
        assertThatThrownBy(()->service.resolve(auth,44)).hasMessage("PUSH_TARGET_NOT_FOUND");
        when(mapper.ownedWithdrawal("WD-ABC123",7)).thenReturn(1);
        assertThat(service.resolve(auth,44).route()).isEqualTo(route);
        for(String invalid:java.util.List.of("/pages/me/wallet-withdraw-tracking",route+"&url=x",
                "/pages/me/wallet-withdraw-tracking?id=WD-abc","/pages/me/wallet-withdraw-tracking?id=other"))
            assertThatThrownBy(()->NativePushService.route(new Notification(44,"FUNDS_RESULT",invalid,"en")))
                .hasMessage("PUSH_ROUTE_UNAVAILABLE");
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
