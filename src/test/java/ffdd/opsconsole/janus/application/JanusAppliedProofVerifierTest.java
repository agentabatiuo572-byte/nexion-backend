package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.janus.mapper.JanusTakeoverMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JanusAppliedProofVerifierTest {
    private static final long NOW=System.currentTimeMillis();
    private final JanusTakeoverMapper mapper=mock(JanusTakeoverMapper.class);

    @Test
    void productionAcceptsConfiguredDeviceSignatureAndPersistsReadback() throws Exception {
        byte[] key="production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
        var unsigned=request("PRODUCTION","executor-1","",NOW);
        String signature=hmac(key,JanusAppliedProofVerifier.canonical(42L,"SID-1",unsigned));
        var signed=request("PRODUCTION","executor-1",signature,NOW);
        String proofHash=sha256(JanusAppliedProofVerifier.canonical(42L,"SID-1",signed)+"\n"+signature);
        when(mapper.findProofHash("executor-1","a".repeat(32))).thenReturn(null,proofHash);
        when(mapper.claimAppliedProof(any(),any(),any(),any(),any(),anyLong(),any(),any(),any(),anyLong(),any(),any(),any(),any(),anyLong())).thenReturn(1);
        JanusAppliedProofVerifier verifier=verifier("PRODUCTION","executor-1:device-1:"+Base64.getEncoder().encodeToString(key));

        JanusAppliedProofVerifier.Verification result=verifier.verify(42L,"SID-1",Map.of(),signed);

        assertThat(result.accepted()).isTrue();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.proofMode()).isEqualTo("PRODUCTION");
    }

    @Test
    void ordinaryUserReceiptWithoutDeviceSignatureIsRejectedWithoutPersistence() {
        JanusAppliedProofVerifier.Verification result=verifier("PRODUCTION","").verify(
                42L,"SID-1",Map.of(),request("PRODUCTION","executor-1","forged",NOW));
        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_APPLIED_PROOF_SIGNATURE_INVALID");
        verify(mapper,never()).claimAppliedProof(any(),any(),any(),any(),any(),anyLong(),any(),any(),any(),anyLong(),any(),any(),any(),any(),anyLong());
    }

    @Test
    void sandboxProofCanNeverCrossProductionTrustDomain() {
        JanusAppliedProofVerifier.Verification result=verifier("PRODUCTION","").verify(
                42L,"SID-1",Map.of(),request("SANDBOX","sandbox","sandbox-token",NOW));
        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_SANDBOX_PROOF_ISOLATION_MISMATCH");
    }

    @Test
    void productionAppliedProofIsDisabledByDefaultUntilNativeHandoffAttestationShips() {
        JanusAppliedProofVerifier disabled = new JanusAppliedProofVerifier(mapper,"PRODUCTION","sandbox-token",
                "42","device-1","approved","",120_000L,false);

        JanusAppliedProofVerifier.Verification result=disabled.verify(42L,"SID-1",Map.of(),
                request("PRODUCTION","executor-1","b".repeat(64),NOW));

        assertThat(result.accepted()).isFalse();
        assertThat(result.error()).isEqualTo("JANUS_PRODUCTION_APPLIED_HOLD");
        verify(mapper,never()).claimAppliedProof(any(),any(),any(),any(),any(),anyLong(),any(),any(),any(),anyLong(),any(),any(),any(),any(),anyLong());
    }

    @Test
    void reconciliationAcceptsSignedObservedCommandTupleSoServiceCanPlaceMismatchOnDriftHold() throws Exception {
        byte[] key="production-device-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
        var unsigned=new JanusTakeoverProgressRequest("device-1","cmd-query",9L,"e".repeat(64),7L,
                "SUCCEEDED","older-target",4,11L,9L,"app-1","native:older","cmd-older",8L,3L,
                null,null,null,"reconcile-1","PRODUCTION","executor-1","a".repeat(32),NOW,"");
        String signature=hmac(key,JanusAppliedProofVerifier.canonical(42L,"SID-1",unsigned));
        var signed=new JanusTakeoverProgressRequest("device-1","cmd-query",9L,"e".repeat(64),7L,
                "SUCCEEDED","older-target",4,11L,9L,"app-1","native:older","cmd-older",8L,3L,
                null,null,null,"reconcile-1","PRODUCTION","executor-1","a".repeat(32),NOW,signature);
        String proofHash=sha256(JanusAppliedProofVerifier.canonical(42L,"SID-1",signed)+"\n"+signature);
        when(mapper.findProofHash("executor-1","a".repeat(32))).thenReturn(null,proofHash);
        when(mapper.claimAppliedProof(any(),any(),any(),any(),any(),anyLong(),any(),any(),any(),anyLong(),any(),any(),any(),any(),anyLong())).thenReturn(1);

        JanusAppliedProofVerifier.Verification result=verifier("PRODUCTION","executor-1:device-1:"+
                Base64.getEncoder().encodeToString(key)).verify(42L,"SID-1",Map.of(),signed);

        assertThat(result.accepted()).withFailMessage("reconciliation proof rejected: %s", result.error()).isTrue();
    }

    private JanusAppliedProofVerifier verifier(String mode,String keys){
        return new JanusAppliedProofVerifier(mapper,mode,"sandbox-token","42","device-1","approved",
                keys,120_000L,true);
    }

    private JanusTakeoverProgressRequest request(String mode,String executor,String signature,long timestamp){
        return new JanusTakeoverProgressRequest("device-1","cmd-1",3L,"e".repeat(64),1L,
                "SUCCEEDED","approved",2,9L,3L,"app-1","device:receipt","cmd-1",3L,1L,
                null,null,null,null,mode,executor,"a".repeat(32),timestamp,signature);
    }

    private static String hmac(byte[] key,String value)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String sha256(String value)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
