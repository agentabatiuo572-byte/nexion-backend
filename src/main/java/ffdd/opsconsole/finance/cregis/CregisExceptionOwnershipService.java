package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisExceptionOwnershipMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ownership is a signed, single-use authorization from the actual token Transfer sender. */
@Service @RequiredArgsConstructor
public class CregisExceptionOwnershipService {
    private final CregisProperties config;
    private final CregisExceptionOwnershipMapper db;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisExceptionAdminGuard admins;

    public record SourceRef(String type,long id) {
        public SourceRef {
            if(!java.util.Set.of("EVENT","OBSERVATION").contains(String.valueOf(type)) || id<=0)
                throw new BizException(422,"CREGIS_EXCEPTION_SOURCE_INVALID");
        }
    }
    public record Evidence(SourceRef ref,CregisExceptionSourceService.Source source,String fromAddress,
                            String sourceStatus,Long userId,long blockNumber,String blockHash,int confirmations,
                            String evidenceHash,Instant checkedAt) { }
    public record VerifiedOwnership(String challengeId,String action,Long targetUserId,Evidence evidence,
                                     String signatureHash) { }
    public record Challenge(String challengeId,String message,String expiresAt,String evidenceHash,
                             String fromAddress,String txid,int logIndex,String grossAmount) { }

    public Evidence preflight(SourceRef ref) {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER || config.getProjectId()<=0)
            throw fail("CREGIS_PROVIDER_DISABLED");
        Map<String,Object> row="EVENT".equals(ref.type())?db.event(config.getProjectId(),ref.id()):db.observation(config.getProjectId(),ref.id());
        if(row==null) throw fail("CREGIS_EXCEPTION_SOURCE_MISSING");
        String state=String.valueOf(row.get("status"));
        if("EVENT".equals(ref.type()) && !java.util.Set.of("DUST_HOLD","REVIEW_HOLD").contains(state)
                || "OBSERVATION".equals(ref.type()) && !"UNATTRIBUTED_HOLD".equals(state)) throw fail("CREGIS_EXCEPTION_SOURCE_CHANGED");
        String txid=normalizeTx((String)row.get("txid"));
        int index=((Number)row.get("logIndex")).intValue();
        String address=((String)row.get("address")).toLowerCase(java.util.Locale.ROOT);
        BigDecimal amount=new BigDecimal(String.valueOf(row.get("grossAmount")));
        long height=((Number)row.get("blockNumber")).longValue();
        Map<String,Object> allocation=db.address(config.getProjectId(),address);
        if(allocation==null || allocation.get("creationBlock")==null
                || height<=((Number)allocation.get("creationBlock")).longValue()) throw fail("CREGIS_EXCEPTION_PROJECT_ADDRESS_UNVERIFIED");
        if(!router.provider().addressBelongs(CregisConstants.BSC_CHAIN_ID,address)) throw fail("CREGIS_EXCEPTION_PROJECT_ADDRESS_UNVERIFIED");
        var proof=chain.verifyExact(txid,index,address,amount,height);
        if(proof==null || proof.confirmations()<Math.max(15,config.getDepositConfirmations())) throw fail("CREGIS_EXCEPTION_CHAIN_NOT_FINAL");
        if(!proof.senderEoa()) throw fail("CREGIS_EXCEPTION_EOA_OWNERSHIP_REQUIRED");
        if(row.get("blockHash")!=null && !proof.blockHash().equalsIgnoreCase((String)row.get("blockHash"))) throw fail("CREGIS_EXCEPTION_CHAIN_CHANGED");
        var matches=router.provider().depositsByTxid(txid).stream().filter(t->t.status()==1
                && txid.equalsIgnoreCase(normalizeTx(t.txid())) && address.equalsIgnoreCase(t.address())
                && amount.compareTo(t.amount())==0 && (row.get("cid")==null || ((Number)row.get("cid")).longValue()==t.cid())).toList();
        if(matches.size()!=1) throw fail("CREGIS_EXCEPTION_PROVIDER_MISMATCH");
        var trade=matches.get(0);
        if(!CregisConstants.BSC_CHAIN_ID.equals(trade.chainId()) || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(trade.tokenId()))
            throw fail("CREGIS_EXCEPTION_PROVIDER_MISMATCH");
        Map<String,Object> existing=db.eventByLog(config.getProjectId(),txid,index);
        if(existing!=null && (!"EVENT".equals(ref.type()) || ((Number)existing.get("id")).longValue()!=ref.id()))
            throw fail("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        var existingCid=db.eventByCid(config.getProjectId(),trade.cid());
        if(existingCid!=null && (!"EVENT".equals(ref.type()) || ((Number)existingCid.get("id")).longValue()!=ref.id()))
            throw fail("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        Long user=row.get("userId") instanceof Number n?n.longValue():null;
        if("EVENT".equals(ref.type())) {
            if(user==null || !(allocation.get("userId") instanceof Number owner) || owner.longValue()!=user
                    || !(allocation.get("allocationBlock") instanceof Number at) || height<=at.longValue()
                    || !(allocation.get("allocationHash") instanceof String anchor)
                    || !anchor.equalsIgnoreCase(chain.blockHash(at.longValue()))) throw fail("CREGIS_EXCEPTION_OWNER_CONFLICT");
        } else if(allocation.get("userId") instanceof Number && allocation.get("allocationBlock") instanceof Number at
                && height>at.longValue()) throw fail("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        var source=new CregisExceptionSourceService.Source(config.getProjectId(),trade.cid(),txid,index,address,amount);
        String hash=sha(String.join("|","CREGIS_EXCEPTION_V1",deployment(),ref.type(),Long.toString(ref.id()),
                Long.toString(source.projectId()),Long.toString(source.cid()),txid,Integer.toString(index),address,
                amount.stripTrailingZeros().toPlainString(),proof.fromAddress(),Long.toString(height),proof.blockHash(),state,
                String.valueOf(user),String.valueOf(allocation.get("userId")),String.valueOf(allocation.get("allocationBlock")),
                String.valueOf(allocation.get("allocationHash"))));
        return new Evidence(ref,source,proof.fromAddress(),state,user,height,proof.blockHash(),proof.confirmations(),hash,Instant.now());
    }

    public Challenge prepare(SourceRef ref,String action,Long targetUserId) {
        long actor=admins.require(true); requireAction(action,targetUserId);
        Evidence proof=preflight(ref);
        if("CLAIM_CREDIT".equals(action) && !"OBSERVATION".equals(ref.type())) throw fail("CREGIS_EXCEPTION_SOURCE_ALREADY_ATTRIBUTED");
        String id=UUID.randomUUID().toString();
        Instant expires=Instant.now().plusSeconds(600);
        String message=String.join("\n","UVEL Cregis ownership authorization v1","Issuer: "+deployment(),
                "Action: "+action,"Project: "+config.getProjectId(),"Chain: 2510 / BSC 56",
                "Token: "+CregisConstants.USDT_BEP20_TOKEN_ID,"Transaction: "+proof.source().txid(),
                "Log index: "+proof.source().logIndex(),"Amount USDT: "+proof.source().grossAmount().stripTrailingZeros().toPlainString(),
                "Deposit recipient: "+proof.source().address(),"Transfer sender: "+proof.fromAddress(),
                "Target UVEL user: "+(targetUserId==null?"NONE":targetUserId),"Evidence: "+proof.evidenceHash(),
                "Nonce: "+id,"Expires UTC: "+expires.toString(),
                "I authorize only this disposition of this deposit. This signature does not authorize wallet access.");
        if(db.insert(id,config.getProjectId(),ref.type(),ref.id(),action,targetUserId,message,proof.evidenceHash(),
                LocalDateTime.ofInstant(expires,ZoneOffset.UTC),actor)!=1) throw fail("CREGIS_EXCEPTION_CHALLENGE_WRITE_FAILED");
        return new Challenge(id,message,expires.toString(),proof.evidenceHash(),proof.fromAddress(),proof.source().txid(),proof.source().logIndex(),proof.source().grossAmount().toPlainString());
    }

    public VerifiedOwnership verify(String challengeId,String signature,String action,Long targetUserId) {
        requireAction(action,targetUserId);
        var challenge=db.challenge(challengeId);
        requireChallenge(challenge,action,targetUserId);
        if(challenge.get("consumed_ref")!=null)throw fail("CREGIS_EXCEPTION_CHALLENGE_USED");
        Evidence evidence=preflight(new SourceRef((String)challenge.get("source_type"),((Number)challenge.get("source_id")).longValue()));
        if(!evidence.evidenceHash().equals(challenge.get("evidence_hash"))) throw fail("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
        String signer=recoverSigner((String)challenge.get("message"),signature);
        if(!evidence.fromAddress().equalsIgnoreCase(signer)) throw fail("CREGIS_EXCEPTION_SIGNATURE_INVALID");
        return new VerifiedOwnership(challengeId,action,targetUserId,evidence,sha(signature.toLowerCase(java.util.Locale.ROOT)));
    }

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Evidence consume(VerifiedOwnership verified,String reference) {
        requireFresh(verified.evidence());
        var challenge=db.lockChallenge(verified.challengeId());
        requireChallenge(challenge,verified.action(),verified.targetUserId());
        if(!verified.evidence().evidenceHash().equals(challenge.get("evidence_hash"))) throw fail("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
        if(reference==null || reference.isBlank() || reference.length()>96) throw fail("CREGIS_EXCEPTION_REQUEST_INVALID");
        if(challenge.get("consumed_ref")!=null) {
            if(reference.equals(challenge.get("consumed_ref"))) return verified.evidence();
            throw fail("CREGIS_EXCEPTION_CHALLENGE_USED");
        }
        if(db.consume(verified.challengeId(),reference)!=1) throw fail("CREGIS_EXCEPTION_CHALLENGE_USED");
        return verified.evidence();
    }

    public void requireFresh(Evidence evidence) {
        if(evidence==null || evidence.checkedAt()==null || !Instant.now().isBefore(evidence.checkedAt().plusSeconds(5)))
            throw fail("CREGIS_EXCEPTION_EVIDENCE_EXPIRED");
    }
    public void requireConsumed(String id,String reference,String action,Long target,String hash) {
        requireAction(action,target);
        var row=db.challenge(id);
        if(row==null || ((Number)row.get("project_id")).longValue()!=config.getProjectId()
                || !action.equals(row.get("action")) || !Objects.equals(target,row.get("target_user_id") instanceof Number n?n.longValue():null)
                || !reference.equals(row.get("consumed_ref")) || !hash.equals(row.get("evidence_hash")))
            throw fail("CREGIS_EXCEPTION_OWNERSHIP_RECEIPT_MISMATCH");
    }
    public void requireTargetActive(long target){if(!db.activeTarget(target))throw fail("CREGIS_EXCEPTION_TARGET_INVALID");}
    private void requireAction(String action,Long target) {
        if(!java.util.Set.of("CLAIM_CREDIT","REFUND_ORIGIN").contains(String.valueOf(action))
                || "CLAIM_CREDIT".equals(action) && (target==null || target<=0 || !db.activeTarget(target))
                || target!=null && target<=0) throw fail("CREGIS_EXCEPTION_TARGET_INVALID");
    }
    private void requireChallenge(Map<String,Object> c,String action,Long target) {
        if(c==null || ((Number)c.get("project_id")).longValue()!=config.getProjectId()
                || !action.equals(c.get("action")) || !Objects.equals(target,c.get("target_user_id") instanceof Number n?n.longValue():null)
                || !expiry(c.get("expires_at")).isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw fail("CREGIS_EXCEPTION_CHALLENGE_INVALID_OR_EXPIRED");
    }
    private LocalDateTime expiry(Object value) {
        if(value instanceof LocalDateTime t)return t;
        if(value instanceof java.sql.Timestamp t)return t.toLocalDateTime();
        throw fail("CREGIS_EXCEPTION_CHALLENGE_INVALID_OR_EXPIRED");
    }
    private String deployment() {
        try {
            var uri=java.net.URI.create(config.getCallbackBaseUrl());
            String path=uri.getPath();
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getHost().isBlank()
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || path==null || path.isBlank() || "/".equals(path) || path.contains("\\")
                    || !path.equals(uri.getRawPath()) || !path.equals(uri.normalize().getPath()))
                throw fail("CREGIS_EXCEPTION_DEPLOYMENT_UNVERIFIED");
            String port=uri.getPort()==-1 || uri.getPort()==443?"":":"+uri.getPort();
            return "https://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)+port+path.replaceAll("/+$","")+" | project="+config.getProjectId();
        } catch(IllegalArgumentException invalid){throw fail("CREGIS_EXCEPTION_DEPLOYMENT_UNVERIFIED");}
    }
    static String recoverSigner(String message,String signature) {
        try {
            if(message==null || signature==null || !signature.matches("(?i)0x[0-9a-f]{130}")) throw new IllegalArgumentException();
            byte[] sig=HexFormat.of().parseHex(signature.substring(2));
            BigInteger r=new BigInteger(1,java.util.Arrays.copyOfRange(sig,0,32));
            BigInteger s=new BigInteger(1,java.util.Arrays.copyOfRange(sig,32,64));
            int v=Byte.toUnsignedInt(sig[64]); if(v>=27)v-=27;
            var curve=CustomNamedCurves.getByName("secp256k1"); var n=curve.getN();
            if(v>1 || r.signum()<=0 || r.compareTo(n)>=0 || s.signum()<=0 || s.compareTo(n.shiftRight(1))>0) throw new IllegalArgumentException();
            byte[] encoded=new byte[33]; encoded[0]=(byte)(2+v);
            byte[] rb=r.toByteArray(); System.arraycopy(rb,Math.max(0,rb.length-32),encoded,33-Math.min(32,rb.length),Math.min(32,rb.length));
            var R=curve.getCurve().decodePoint(encoded);
            if(!R.multiply(n).isInfinity()) throw new IllegalArgumentException();
            byte[] body=message.getBytes(StandardCharsets.UTF_8);
            byte[] prefix=("\u0019Ethereum Signed Message:\n"+body.length).getBytes(StandardCharsets.UTF_8);
            byte[] joined=new byte[prefix.length+body.length];System.arraycopy(prefix,0,joined,0,prefix.length);System.arraycopy(body,0,joined,prefix.length,body.length);
            BigInteger e=new BigInteger(1,keccak(joined));
            var pub=ECAlgorithms.sumOfTwoMultiplies(curve.getG(),e.negate().mod(n),R,s).multiply(r.modInverse(n)).normalize().getEncoded(false);
            byte[] hashed=keccak(java.util.Arrays.copyOfRange(pub,1,pub.length));
            return "0x"+HexFormat.of().formatHex(java.util.Arrays.copyOfRange(hashed,12,32));
        } catch(RuntimeException invalid){throw fail("CREGIS_EXCEPTION_SIGNATURE_INVALID");}
    }
    private static byte[] keccak(byte[] bytes){var digest=new KeccakDigest(256);digest.update(bytes,0,bytes.length);byte[] result=new byte[32];digest.doFinal(result,0);return result;}
    static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String normalizeTx(String txid){if(txid==null || !txid.matches("(?i)(0x)?[0-9a-f]{64}"))throw fail("CREGIS_EXCEPTION_SOURCE_INVALID");return "0x"+txid.replaceFirst("(?i)^0x","").toLowerCase(java.util.Locale.ROOT);}
    private static BizException fail(String message){return new BizException(409,message);}
}
