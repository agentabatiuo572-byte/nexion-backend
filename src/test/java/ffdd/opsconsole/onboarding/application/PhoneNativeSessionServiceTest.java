package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.onboarding.mapper.PhoneNativeSessionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PhoneNativeSessionServiceTest {
    private final PhoneNativeSessionMapper mapper = mock(PhoneNativeSessionMapper.class);
    private final AndroidPhoneAttestationVerifier verifier = mock(AndroidPhoneAttestationVerifier.class);
    private final PhoneNativeSessionService service = new PhoneNativeSessionService(mapper, verifier,
            Clock.fixed(Instant.ofEpochMilli(10000), ZoneOffset.UTC));
    private UsernamePasswordAuthenticationToken auth(String session) {
        var auth = new UsernamePasswordAuthenticationToken("42", "", List.of());
        auth.setDetails(Map.of("subjectType", "USER", "sessionId", session));
        return auth;
    }
    @Test void browserSessionCannotReuseAnotherInstallationOrExpiredProof() {
        assertThatThrownBy(() -> service.require(auth("web"), "phone"))
                .isInstanceOf(BizException.class).hasMessageContaining("PHONE_NATIVE_SESSION_REQUIRED");
        when(mapper.read(42, "app")).thenReturn(new PhoneNativeSessionMapper.Session("phone", "", "", 0, 10001));
        service.require(auth("app"), "phone");
        assertThatThrownBy(() -> service.require(auth("app"), "another"))
                .isInstanceOf(BizException.class);
        when(mapper.read(42, "app")).thenReturn(new PhoneNativeSessionMapper.Session("phone", "", "", 0, 10000));
        assertThatThrownBy(() -> service.require(auth("app"), "phone"))
                .isInstanceOf(BizException.class);
    }
    @Test void challengeBindsAccountSessionAndInstallation() {
        var result = service.challenge(auth("app"), "phone");
        String payload = new String(java.util.Base64.getDecoder().decode((String) result.get("payload")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).startsWith("phone-installation-v1\n42\napp\nphone\n");
        assertThat(result.get("expiresAt")).isEqualTo(130000L);
        verify(mapper).challenge(eq(42L),eq("app"),eq("phone"),eq((String)result.get("nonce")),eq((String)result.get("payload")),eq(130000L));
    }
    @Test void proofIsConsumedOnceAndRequiresTheSavedKey() {
        var pending = new PhoneNativeSessionMapper.Session("phone", "nonce", "payload", 10001, 0);
        when(mapper.lock(42,"app")).thenReturn(pending,
                new PhoneNativeSessionMapper.Session("phone", "", "", 0, 910000));
        when(mapper.knownKey(42,"phone")).thenReturn(null,"key");
        when(verifier.verify(List.of("cert"),"nonce","payload","sig",null)).thenReturn("key");
        when(mapper.verify(42,"app","nonce",910000)).thenReturn(1);
        var proof = new PhoneNativeSessionService.Proof("phone", List.of("cert"), "sig");
        assertThat(service.verify(auth("app"),proof).get("verifiedUntil")).isEqualTo(910000L);
        assertThatThrownBy(() -> service.verify(auth("app"),proof)).isInstanceOf(BizException.class)
                .hasMessageContaining("PHONE_NATIVE_CHALLENGE_EXPIRED");
        verify(verifier,times(1)).verify(anyList(),anyString(),anyString(),anyString(),isNull());
    }
    @Test void expiredChallengeDoesNotReachAttestationOrPersistAnything() {
        when(mapper.lock(42,"app")).thenReturn(new PhoneNativeSessionMapper.Session("phone","nonce","payload",10000,0));
        assertThatThrownBy(() -> service.verify(auth("app"),new PhoneNativeSessionService.Proof("phone",List.of(),"sig")))
                .isInstanceOf(BizException.class).hasMessageContaining("PHONE_NATIVE_CHALLENGE_EXPIRED");
        verifyNoInteractions(verifier);
        verify(mapper,never()).saveKey(anyLong(),anyString(),anyString());
    }
    @Test void phoneTasksRequireCurrentNativeSessionButPurchasedDevicesDoNot() {
        when(mapper.deviceBinding(42,1)).thenReturn(new PhoneNativeSessionMapper.DeviceBinding("MOBILE","phone"));
        when(mapper.deviceBinding(42,2)).thenReturn(new PhoneNativeSessionMapper.DeviceBinding("BOX",null));
        when(mapper.taskDevice(42,"task")).thenReturn(1L);
        assertThatThrownBy(() -> service.requireForDevice(auth("web"),1L)).hasMessageContaining("PHONE_NATIVE_SESSION_REQUIRED");
        assertThatThrownBy(() -> service.requireForTask(auth("web"),"task")).hasMessageContaining("PHONE_NATIVE_SESSION_REQUIRED");
        service.requireForDevice(auth("web"),2L);
        when(mapper.read(42,"app")).thenReturn(new PhoneNativeSessionMapper.Session("phone","","",0,10001));
        when(mapper.executionInstallation(42)).thenReturn("phone");
        service.requireForTask(auth("app"),"task");
        var order = inOrder(mapper);
        order.verify(mapper).lockUser(42L);
        order.verify(mapper).executionInstallation(42L);
        when(mapper.executionInstallation(42)).thenReturn("new-phone");
        assertThatThrownBy(() -> service.requireRuntime(auth("app"),"phone")).hasMessageContaining("TASK_ASSIGNMENT_PHONE_BINDING_INVALID");
        service.requireForDevice(auth("web"),2L);
        verify(mapper,never()).read(42,"unrelated");
    }
}
