package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.Set;
import org.bouncycastle.asn1.*;
import org.junit.jupiter.api.Test;

class AndroidPhoneAttestationVerifierTest {
    private static final byte[] NONCE = new byte[32];
    private static final byte[] DIGEST = new byte[32];
    private byte[] extension(boolean hardware,boolean locked,int bootState,String packageName) throws Exception {
        var app = new DERSequence(new ASN1Encodable[]{
                new DERSet(new DERSequence(new ASN1Encodable[]{new DEROctetString(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new ASN1Integer(1)})),
                new DERSet(new DEROctetString(DIGEST))});
        var software = new DERSequence(new DERTaggedObject(true,709,new DEROctetString(app.getEncoded())));
        var root = new DERSequence(new ASN1Encodable[]{new DEROctetString(new byte[32]),ASN1Boolean.getInstance(locked),new ASN1Enumerated(bootState),new DEROctetString(new byte[32])});
        var enforced = new DERSequence(new DERTaggedObject(true,704,root));
        var key = new DERSequence(new ASN1Encodable[]{new ASN1Integer(400),new ASN1Enumerated(hardware?1:0),
                new ASN1Integer(400),new ASN1Enumerated(hardware?1:0),new DEROctetString(NONCE),new DEROctetString(new byte[0]),software,enforced});
        return new DEROctetString(key.getEncoded()).getEncoded();
    }
    private void check(byte[] extension,String nonce) {
        AndroidPhoneAttestationVerifier.validateExtension(extension,nonce,"app.test",Set.of("0".repeat(64)));
    }
    @Test void acceptsMatchingHardwareChallengeAndRejectsSoftwareUnlockedWrongAppOrNonce() throws Exception {
        String nonce = Base64.getEncoder().encodeToString(NONCE);
        check(extension(true,true,0,"app.test"),nonce);
        assertThatThrownBy(() -> check(extension(false,true,0,"app.test"),nonce)).hasMessageContaining("PHONE_NATIVE_PROOF_INVALID");
        assertThatThrownBy(() -> check(extension(true,false,0,"app.test"),nonce)).hasMessageContaining("PHONE_NATIVE_PROOF_INVALID");
        assertThatThrownBy(() -> check(extension(true,true,2,"app.test"),nonce)).hasMessageContaining("PHONE_NATIVE_PROOF_INVALID");
        assertThatThrownBy(() -> check(extension(true,true,0,"other.app"),nonce)).hasMessageContaining("PHONE_NATIVE_PROOF_INVALID");
        assertThatThrownBy(() -> check(extension(true,true,0,"app.test"),"AQ==")).hasMessageContaining("PHONE_NATIVE_PROOF_INVALID");
    }
}
