package ffdd.opsconsole.content.push;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.push.mapper.NativePushMapper;
import ffdd.opsconsole.content.push.mapper.NativePushMapper.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class NativePushDispatcherTest {
    final NativePushMapper mapper=mock(NativePushMapper.class);
    final AuthSessionMapper sessions=mock(AuthSessionMapper.class);
    final UserAccountBlocklistVerifier blocklist=mock(UserAccountBlocklistVerifier.class);
    final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    final DirectPushClient provider=mock(DirectPushClient.class);
    final Environment env=mock(Environment.class);
    final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    final NativePushDispatcher dispatcher=new NativePushDispatcher(mapper,sessions,blocklist,config,provider,env,tx);
    final Delivery delivery=new Delivery(1,22,"install-1",7,4,1,"lease");
    final Installation install=new Installation("install-1","fcm:"+"x".repeat(150),"FCM", "hash",7L,"chain","android","granted","1",4,true);

    void valid() {
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(sessions.lockAccount(7)).thenReturn(7L); when(mapper.lockInstallation("install-1")).thenReturn(install);
        when(mapper.lockClaim(1,"lease")).thenReturn(delivery); when(mapper.activeSession(install,0,30)).thenReturn(1);
        when(mapper.stillEligible(22,7)).thenReturn(1);
        when(mapper.ownedNotification(22,7)).thenReturn(new Notification(22,"FUNDS_RESULT","/pages/me/wallet-bills","zh"));
    }
    @Test void providerAcceptanceIsSeparateReceiptAndBoundedCall() {
        valid(); when(provider.send(1,22,"FCM","fcm:"+"x".repeat(150),"zh")).thenReturn(new DirectPushClient.Result("ACCEPTED","FCM_ACCEPTED","task"));
        dispatcher.send(delivery,0);
        verify(mapper).finish(eq(1L),eq("lease"),eq("ACCEPTED"),eq("FCM_ACCEPTED"),eq("task"),anyInt());
    }
    @Test void oldEpochAndRevokedSessionNeverReachProvider() {
        valid(); when(mapper.lockInstallation("install-1")).thenReturn(new Installation("install-1","fcm:"+"x".repeat(150),"FCM","hash",8L,"other","android","granted","1",5,true));
        dispatcher.send(delivery,0); verifyNoInteractions(provider);
        when(mapper.lockInstallation("install-1")).thenReturn(install); when(mapper.activeSession(install,0,30)).thenReturn(0);
        dispatcher.send(delivery,0); verifyNoInteractions(provider);
    }
    @Test void staleWorkerCannotUpdateNewLease() {
        valid(); when(mapper.lockClaim(1,"lease")).thenReturn(null); dispatcher.send(delivery,0);
        verifyNoInteractions(provider); verify(mapper,never()).finish(anyLong(),any(),any(),any(),any(),anyInt());
    }
    @Test void invalidCidDeactivatesAndRetiresPendingWork() {
        valid(); when(provider.send(anyLong(),anyLong(),any(),any(),any())).thenReturn(new DirectPushClient.Result("INVALID_TOKEN","FCM_UNREGISTERED",null));
        dispatcher.send(delivery,0); verify(mapper).deactivate("install-1"); verify(mapper).cancelPending("install-1");
        verify(mapper).finish(eq(1L),eq("lease"),eq("INVALID_TOKEN"),any(),isNull(),eq(0));
    }
    @Test void missingConfigurationIsBlockedAndRetryExhaustionIsFinite() {
        valid(); when(provider.send(anyLong(),anyLong(),any(),any(),any())).thenReturn(new DirectPushClient.Result("BLOCKED","DISABLED",null));
        dispatcher.send(delivery,0); verify(mapper).finish(1,"lease","BLOCKED","DISABLED",null,300);
        when(provider.send(anyLong(),anyLong(),any(),any(),any())).thenReturn(new DirectPushClient.Result("RETRY","FCM_HTTP_503",null));
        dispatcher.send(new Delivery(1,22,"install-1",7,4,5,"lease"),0);
        verify(mapper).finish(eq(1L),eq("lease"),eq("FAILED"),eq("FCM_HTTP_503"),isNull(),anyInt());
    }
    @Test void legacyProviderNeverReachesNetworkAndRetryAfterIsHonored() {
        valid();
        when(mapper.lockInstallation("install-1")).thenReturn(new Installation("install-1","oldcid123","UNIPUSH","hash",7L,"chain","android","granted","1",4,true));
        dispatcher.send(delivery,0); verifyNoInteractions(provider);
        when(mapper.lockInstallation("install-1")).thenReturn(install);
        when(provider.send(anyLong(),anyLong(),any(),any(),any())).thenReturn(new DirectPushClient.Result("RETRY","FCM_HTTP_429",null,1200));
        dispatcher.send(delivery,0); verify(mapper).finish(1,"lease","RETRY","FCM_HTTP_429",null,1200);
    }
    @Test void blockedUserAndExpiredMessageCannotSend() {
        valid(); when(blocklist.isBlocked(7L)).thenReturn(true); dispatcher.send(delivery,0); verifyNoInteractions(provider);
        when(blocklist.isBlocked(7L)).thenReturn(false); when(mapper.stillEligible(22,7)).thenReturn(0);
        dispatcher.send(delivery,0); verifyNoInteractions(provider);
    }
}
