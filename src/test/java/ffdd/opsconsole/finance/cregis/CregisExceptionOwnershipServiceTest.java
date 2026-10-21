package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionOwnershipMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.bouncycastle.crypto.digests.*;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.*;
import org.junit.jupiter.api.*;

class CregisExceptionOwnershipServiceTest {
    static final String TX="0x"+"a".repeat(64), BLOCK="0x"+"b".repeat(64), ADDRESS="0x"+"c".repeat(40);
    static final String KEY_ONE_ADDRESS="0x7e5f4552091a69125d5dfcb7b8c2659029395bdf";
    final CregisProperties config=new CregisProperties();
    final CregisExceptionOwnershipMapper db=mock(CregisExceptionOwnershipMapper.class);
    final CregisGatewayRouter router=mock(CregisGatewayRouter.class);
    final CregisGateway provider=mock(CregisGateway.class);
    final BscDepositProof chain=mock(BscDepositProof.class);
    final CregisExceptionAdminGuard admins=mock(CregisExceptionAdminGuard.class);
    final CregisExceptionOwnershipService service=new CregisExceptionOwnershipService(config,db,router,chain,admins);
    final Map<String,Object> challenge=new HashMap<>();
    @BeforeEach void setup() {
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);
        config.setCallbackBaseUrl("https://test.uvel.invalid/api/cregis/callbacks");
        when(admins.require(true)).thenReturn(7L);when(db.activeTarget(42)).thenReturn(true);
        when(db.observation(88,101)).thenReturn(Map.of("id",101L,"txid",TX,"logIndex",3,"address",ADDRESS,
                "grossAmount","10.000000","blockNumber",20L,"status","UNATTRIBUTED_HOLD"));
        when(db.eventByLog(anyLong(),anyString(),anyInt())).thenReturn(null);
        when(db.eventByCid(anyLong(),anyLong())).thenReturn(null);
        when(db.address(88,ADDRESS)).thenReturn(Map.of("id",1L,"state","FUNDED_HOLD","creationBlock",10L));
        when(router.provider()).thenReturn(provider);when(provider.addressBelongs("2510",ADDRESS)).thenReturn(true);
        when(provider.depositsByTxid(TX)).thenReturn(List.of(new CregisGateway.DepositTrade(77,"2510",CregisConstants.USDT_BEP20_TOKEN_ID,ADDRESS,BigDecimal.TEN,TX,1)));
        when(chain.verifyExact(TX,3,ADDRESS,new BigDecimal("10.000000"),20L)).thenReturn(new BscDepositProof.TransferProof(3,20,BLOCK,15,KEY_ONE_ADDRESS,ADDRESS,BigDecimal.TEN,true));
        when(db.insert(anyString(),anyLong(),anyString(),anyLong(),anyString(),nullable(Long.class),anyString(),anyString(),any(),anyLong())).thenAnswer(i->{
            challenge.put("challenge_id",i.getArgument(0));challenge.put("project_id",i.getArgument(1));challenge.put("source_type",i.getArgument(2));
            challenge.put("source_id",i.getArgument(3));challenge.put("action",i.getArgument(4));
            if(i.getArgument(5)!=null)challenge.put("target_user_id",i.getArgument(5));
            challenge.put("message",i.getArgument(6));challenge.put("evidence_hash",i.getArgument(7));
            challenge.put("expires_at",Timestamp.valueOf(i.<LocalDateTime>getArgument(8)));return 1;});
        when(db.challenge(any())).thenAnswer(i->challenge.isEmpty()?null:challenge);
        when(db.lockChallenge(any())).thenAnswer(i->challenge);
        when(db.consume(any(),any())).thenAnswer(i->{challenge.put("consumed_ref",i.getArgument(1));return 1;});
    }
    @Test void eip191RecoveryUsesKnownPrivateKeyOneAddressAndRejectsMessageTampering() {
        String signature=sign("UVEL claim – 所有权",BigInteger.ONE);
        assertThat(CregisExceptionOwnershipService.recoverSigner("UVEL claim – 所有权",signature)).isEqualTo(KEY_ONE_ADDRESS);
        assertThat(CregisExceptionOwnershipService.recoverSigner("UVEL claim – 所有权 changed",signature)).isNotEqualTo(KEY_ONE_ADDRESS);
        assertThatThrownBy(()->CregisExceptionOwnershipService.recoverSigner("x","0x"+"0".repeat(130))).hasMessage("CREGIS_EXCEPTION_SIGNATURE_INVALID");
    }
    @Test void nonceBindsFullSourceEnvironmentActionUserAndCanOnlyBeConsumedOnce() {
        var c=service.prepare(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101),"CLAIM_CREDIT",42L);
        assertThat(c.message()).contains("Issuer: https://test.uvel.invalid/api/cregis/callbacks | project=88","Action: CLAIM_CREDIT","Project: 88","Chain: 2510 / BSC 56",
                "Log index: 3","Target UVEL user: 42",KEY_ONE_ADDRESS,TX,ADDRESS,"Nonce: "+c.challengeId());
        var verified=service.verify(c.challengeId(),sign(c.message(),BigInteger.ONE),"CLAIM_CREDIT",42L);
        assertThat(verified.evidence().source().cid()).isEqualTo(77);
        assertThatThrownBy(()->service.verify(c.challengeId(),sign(c.message(),BigInteger.ONE),"REFUND_ORIGIN",42L)).hasMessage("CREGIS_EXCEPTION_CHALLENGE_INVALID_OR_EXPIRED");
        service.consume(verified,"CL-original");
        assertThatThrownBy(()->service.consume(verified,"CL-another")).hasMessage("CREGIS_EXCEPTION_CHALLENGE_USED");
        assertThatThrownBy(()->service.verify(c.challengeId(),sign(c.message(),BigInteger.ONE),"CLAIM_CREDIT",42L)).hasMessage("CREGIS_EXCEPTION_CHALLENGE_USED");
    }
    @Test void wrongSignerExpiredChallengeAndChangedCanonicalEvidenceCannotPropose() {
        var c=service.prepare(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101),"CLAIM_CREDIT",42L);
        assertThatThrownBy(()->service.verify(c.challengeId(),sign(c.message(),BigInteger.TWO),"CLAIM_CREDIT",42L)).hasMessage("CREGIS_EXCEPTION_SIGNATURE_INVALID");
        challenge.put("expires_at",Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
        assertThatThrownBy(()->service.verify(c.challengeId(),sign(c.message(),BigInteger.ONE),"CLAIM_CREDIT",42L)).hasMessage("CREGIS_EXCEPTION_CHALLENGE_INVALID_OR_EXPIRED");
        challenge.put("expires_at",Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        config.setCallbackBaseUrl("https://production.uvel.invalid/api/cregis/callbacks");
        assertThatThrownBy(()->service.verify(c.challengeId(),sign(c.message(),BigInteger.ONE),"CLAIM_CREDIT",42L)).hasMessage("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
    }
    @Test void contractSenderLaterAllocatedOwnerAndExistingCreditRemainUnclaimable() {
        when(chain.verifyExact(anyString(),anyInt(),anyString(),any(),any())).thenReturn(new BscDepositProof.TransferProof(3,20,BLOCK,15,KEY_ONE_ADDRESS,ADDRESS,BigDecimal.TEN,false));
        assertThatThrownBy(()->service.preflight(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101))).hasMessage("CREGIS_EXCEPTION_EOA_OWNERSHIP_REQUIRED");
        when(chain.verifyExact(anyString(),anyInt(),anyString(),any(),any())).thenReturn(new BscDepositProof.TransferProof(3,20,BLOCK,15,KEY_ONE_ADDRESS,ADDRESS,BigDecimal.TEN,true));
        when(db.address(88,ADDRESS)).thenReturn(Map.of("id",1L,"state","READY","creationBlock",10L,"userId",42L,"allocationBlock",15L));
        assertThatThrownBy(()->service.preflight(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101))).hasMessage("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        when(db.address(88,ADDRESS)).thenReturn(Map.of("id",1L,"state","FUNDED_HOLD","creationBlock",10L));
        when(db.eventByLog(88,TX,3)).thenReturn(Map.of("id",2L,"status","CREDITED","userId",77L));
        assertThatThrownBy(()->service.preflight(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101))).hasMessage("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        when(db.eventByLog(88,TX,3)).thenReturn(null);
        when(db.eventByCid(88,77)).thenReturn(Map.of("id",2L,"status","CREDITED","userId",77L));
        assertThatThrownBy(()->service.preflight(new CregisExceptionOwnershipService.SourceRef("OBSERVATION",101))).hasMessage("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
    }
    static String sign(String message,BigInteger privateKey) {
        byte[] body=message.getBytes(StandardCharsets.UTF_8),prefix=("\u0019Ethereum Signed Message:\n"+body.length).getBytes(StandardCharsets.UTF_8);
        var digest=new KeccakDigest(256);digest.update(prefix,0,prefix.length);digest.update(body,0,body.length);byte[] hash=new byte[32];digest.doFinal(hash,0);
        var c=CustomNamedCurves.getByName("secp256k1");var domain=new ECDomainParameters(c.getCurve(),c.getG(),c.getN(),c.getH());
        var signer=new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));signer.init(true,new ECPrivateKeyParameters(privateKey,domain));
        BigInteger[] rs=signer.generateSignature(hash);if(rs[1].compareTo(c.getN().shiftRight(1))>0)rs[1]=c.getN().subtract(rs[1]);
        byte[] sig=new byte[65];fixed(rs[0],sig,0);fixed(rs[1],sig,32);
        byte[] pub=c.getG().multiply(privateKey).getEncoded(false);var kd=new KeccakDigest(256);kd.update(pub,1,64);byte[] out=new byte[32];kd.doFinal(out,0);
        String expected="0x"+HexFormat.of().formatHex(Arrays.copyOfRange(out,12,32));
        for(int v=27;v<=28;v++){sig[64]=(byte)v;String candidate="0x"+HexFormat.of().formatHex(sig);
            if(expected.equals(CregisExceptionOwnershipService.recoverSigner(message,candidate)))return candidate;}
        throw new AssertionError("No recovery id");
    }
    static void fixed(BigInteger value,byte[] target,int offset){byte[] bytes=value.toByteArray();System.arraycopy(bytes,Math.max(0,bytes.length-32),target,offset+32-Math.min(32,bytes.length),Math.min(32,bytes.length));}
}
