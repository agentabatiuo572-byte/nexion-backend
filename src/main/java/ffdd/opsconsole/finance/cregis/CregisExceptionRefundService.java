package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CregisExceptionRefundService {
    private final CregisExceptionRefundMapper db;
    private final CregisDepositMapper deposits;
    private final CregisExceptionOwnershipService ownership;
    private final CregisExceptionSourceService sources;
    private final CregisProperties config;
    private final CregisGatewayRouter router;
    private final CregisPayoutControl transport;
    private final AdminOperatorRoleResolver admins;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final AuditObjectLockMapper locks;

    public Map<String,Object> policy() { admin(false); return view(policySnapshot()); }
    private Map<String,Object> policySnapshot() {
        var policy = db.policy(project());
        if(policy==null||policy.isEmpty())return map("mode","UNCONFIGURED","feeUsdt",BigDecimal.ZERO,"version",0L);
        if(policy.get("mode")==null||!Set.of("UNCONFIGURED","PLATFORM_PAYS","FIXED_USDT").contains(policy.get("mode"))
                ||policy.get("feeUsdt")==null||number(policy,"version")<0)
            throw new BizException(503,"CREGIS_REFUND_POLICY_UNAVAILABLE");
        BigDecimal fee=amount(policy.get("feeUsdt").toString());
        if(!"FIXED_USDT".equals(policy.get("mode"))&&fee.signum()!=0)throw new BizException(503,"CREGIS_REFUND_POLICY_UNAVAILABLE");
        return policy;
    }
    public List<Map<String,Object>> policyCases() { admin(false); return db.policyCases(project()).stream().map(this::view).toList(); }
    public Map<String,Object> proposePolicy(String key,long version,String mode,String fee,String reason) {
        long actor=admin(true); requireReason(reason);
        if (version<0 || mode==null || !Set.of("UNCONFIGURED","PLATFORM_PAYS","FIXED_USDT").contains(mode)) throw invalid();
        BigDecimal amount=amount(fee);
        if (!"FIXED_USDT".equals(mode) && amount.signum()!=0) throw invalid();
        return command(actor,key,map("version",version,"mode",mode,"fee",amount,"reason",reason),()->{
            db.ensurePolicy(project()); var current=db.lockPolicy(project());
            if (number(current,"version")!=version) throw conflict();
            if(db.proposePolicy(project(),version,mode,amount,actor,reason.trim())!=1) throw conflict();
            long id=db.lastId(); record("FEE_PROPOSED",Long.toString(id),actor,map("mode",mode,"fee",amount,"reason",reason));
            return map("id",Long.toString(id),"state","MAKER_DONE","version",0L);
        });
    }
    public Map<String,Object> decidePolicy(String key,long id,long version,String decision,String reason) {
        long actor=admin(true); requireReason(reason); decision(decision,false);
        return command(actor,key,map("policyCaseId",id,"version",version,"decision",decision,"reason",reason),()->{
            var proposal=db.lockPolicyCase(id); requireRow(proposal,version,"MAKER_DONE"); different(proposal,actor);
            db.ensurePolicy(project()); var policy=db.lockPolicy(project());
            if ("APPROVE".equals(decision) && (number(policy,"version")!=number(proposal,"expectedPolicyVersion")
                    || db.applyPolicy(project(),number(policy,"version"),text(proposal,"mode"),decimal(proposal,"feeUsdt"))!=1)) throw conflict();
            String state="APPROVE".equals(decision)?"RESOLVED":"REJECTED";
            if(db.decidePolicy(id,version,actor,state)!=1) throw conflict();
            record("FEE_"+decision,Long.toString(id),actor,map("reason",reason,"mode",proposal.get("mode"),"fee",proposal.get("feeUsdt")));
            return map("id",Long.toString(id),"state",state,"version",version+1);
        });
    }
    public Map<String,Object> preview(String challenge,String signature) {
        admin(true); var evidence=ownership.verify(challenge,signature,"REFUND_ORIGIN",null);
        return view(preview(evidence.evidence(),policySnapshot()));
    }
    private Map<String,Object> preview(CregisExceptionOwnershipService.Evidence evidence,Map<String,Object> policy) {
        requireEligible(evidence); var source=evidence.source();
        boolean configured=!"UNCONFIGURED".equals(policy.get("mode"));
        BigDecimal fee="FIXED_USDT".equals(policy.get("mode"))?decimal(policy,"feeUsdt"):BigDecimal.ZERO;
        BigDecimal net=source.grossAmount().subtract(fee);
        return map("sourceType",evidence.ref().type(),"sourceId",Long.toString(evidence.ref().id()),"txid",source.txid(),
                "logIndex",source.logIndex(),"grossAmount",source.grossAmount(),"feeAmount",fee,"netAmount",configured?net:BigDecimal.ZERO,
                "feePolicyVersion",number(policy,"version"),"feeMode",policy.get("mode"),"returnAddress",evidence.fromAddress(),
                "sourceEvidenceHash",evidence.evidenceHash(),"evidenceHash",snapshotHash(evidence,policy,fee,net),
                "canPropose",configured&&net.signum()>0,"disabledReason",!configured?"CREGIS_REFUND_FEE_NOT_CONFIGURED":net.signum()<=0?"CREGIS_REFUND_NET_NOT_POSITIVE":null);
    }
    public Map<String,Object> propose(String key,String challenge,String signature,String evidenceHash,String reason) {
        long actor=admin(true); requireReason(reason);
        var request=map("challenge",challenge,"hash",evidenceHash,"reason",reason);
        var replay=replay(actor,key,request);if(replay!=null)return replay;
        var verified=ownership.verify(challenge,signature,"REFUND_ORIGIN",null);var evidence=verified.evidence();requireEligible(evidence);
        return command(actor,key,request,()->{
            lockGate();checkLocks(evidence.source(),evidence.userId()); var policy=policySnapshot(); var snapshot=preview(evidence,policy);
            if (!Boolean.TRUE.equals(snapshot.get("canPropose"))) throw new BizException(409,text(snapshot,"disabledReason"));
            if(!Objects.equals(evidenceHash,snapshot.get("evidenceHash"))) throw new BizException(409,"CREGIS_REFUND_EVIDENCE_CHANGED");
            String no="CR-RF-"+UUID.randomUUID().toString().replace("-","");
            ownership.consume(verified,no);
            sources.reserve(evidence.source(),"REFUND",no);
            ownership.requireFresh(evidence);
            var row=map("caseNo",no,"projectId",project(),"sourceType",evidence.ref().type(),"sourceId",evidence.ref().id(),
                    "claimantUserId",evidence.userId(),"txid",evidence.source().txid(),"logIndex",evidence.source().logIndex(),
                    "depositCid",evidence.source().cid(),"returnAddress",evidence.fromAddress(),"depositAddress",evidence.source().address(),
                    "grossAmount",evidence.source().grossAmount(),"feeAmount",snapshot.get("feeAmount"),"netAmount",snapshot.get("netAmount"),
                    "feePolicyVersion",snapshot.get("feePolicyVersion"),"ownershipReceipt",challenge,"evidenceHash",evidenceHash,
                    "sourceEvidenceHash",evidence.evidenceHash(),"ownershipReference",no,"makerId",actor,"reason",reason.trim(),"businessKey",no);
            if(db.insertCase(row)!=1) throw conflict();
            long id=db.lastId(); record("PROPOSED",no,actor,map("reason",reason,"snapshot",snapshot));
            return view(db.byId(project(),id));
        });
    }
    public Map<String,Object> cases(long before,int limit) {
        admin(false); if(before<0||limit<1||limit>50)throw invalid();
        var rows=db.cases(project(),before,limit+1); boolean more=rows.size()>limit;
        var page=rows.stream().limit(limit).map(this::view).toList();
        return map("records",page,"hasMore",more,"nextBeforeId",more?page.get(page.size()-1).get("id"):null);
    }
    public Map<String,Object> detail(long id) { admin(false);return view(required(id)); }
    public Map<String,Object> decide(String key,long id,long version,String decision,String reason) {
        long actor=admin(true); requireReason(reason); decision(decision,true);
        var request=map("caseId",id,"version",version,"decision",decision,"reason",reason);
        var replay=replay(actor,key,request);if(replay!=null)return replay;
        var before=required(id);
        var evidence="APPROVE".equals(decision)?fresh(before):null;
        return command(actor,key,request,()->{
            lockGate(); sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
            var row=db.lockCase(project(),id); requireRow(row,version,"MAKER_DONE");
            checkLocks(source(row),number(row,"claimantUserId"));
            if("CANCEL".equals(decision)) { if(number(row,"makerId")!=actor) throw new BizException(403,"CREGIS_REFUND_MAKER_REQUIRED"); }
            else different(row,actor);
            if(evidence!=null) requireFresh(evidence,row);
            String next="APPROVE".equals(decision)?"APPROVED":"CANCEL".equals(decision)?"CANCELLED":"REJECTED";
            if(db.state(id,version,"MAKER_DONE",next,actor,null)!=1)throw conflict();
            if(!"APPROVE".equals(decision))sources.release(source(row),"REFUND",text(row,"caseNo"));
            record(decision,text(row,"caseNo"),actor,map("reason",reason,"state",next));
            return view(db.byId(project(),id));
        });
    }
    public Map<String,Object> retry(String key,long id,long version,String reason) {
        long actor=admin(true); requireReason(reason);var request=map("retryId",id,"version",version,"reason",reason);
        var replay=replay(actor,key,request);if(replay!=null)return replay;
        var before=required(id); var proof=fresh(before);
        String previous=text(before,"caseNo");
        if(number(before,"providerCid")>0) {
            var observed=query(before,number(before,"providerCid"));
            if(!Set.of(2,3,4).contains(observed.status().providerCode())||observed.txid()!=null)throw conflict();
        } else if(!db.callbackCids(previous).isEmpty())throw conflict();
        return command(actor,key,request,()->{
            lockGate(); sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
            var old=db.lockCase(project(),id); requireRow(old,version,"FAILED_NO_TRANSFER"); requireFresh(proof,old);
            checkLocks(source(old),number(old,"claimantUserId"));
            if(db.successCallback(previous)>0||db.successProgressCount(previous)>0
                    ||number(old,"providerCid")==0&&!db.callbackCids(previous).isEmpty())throw conflict();
            if(db.state(id,version,"FAILED_NO_TRANSFER","REPROPOSED",null,null)!=1)throw conflict();
            String no="CR-RF-"+UUID.randomUUID().toString().replace("-","");
            sources.release(source(old),"REFUND",text(old,"caseNo")); sources.reserve(source(old),"REFUND",no);
            var next=new LinkedHashMap<>(old);next.put("caseNo",no);next.put("businessKey",no);next.put("makerId",actor);next.put("reason",reason.trim());
            if(db.insertCase(next)!=1)throw conflict(); long newId=db.lastId();
            record("REPROPOSED",no,actor,map("previousCaseNo",old.get("caseNo"),"reason",reason));
            return view(db.byId(project(),newId));
        });
    }
    public Map<String,Object> recover(String key,long id,long version,String suppliedCid,String reason) {
        long actor=admin(true); requireReason(reason); long cid=positiveId(suppliedCid);
        var request=map("recoveryId",id,"version",version,"cid",cid,"reason",reason);
        var replay=replay(actor,key,request);if(replay!=null)return replay;
        var before=required(id);
        if(!Set.of("UNKNOWN","SENT").contains(text(before,"state"))||before.get("providerCid")!=null&&number(before,"providerCid")!=cid)throw conflict();
        var observed=query(before,cid);
        return command(actor,key,request,()->{
            lockGate(); sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
            var row=db.lockCase(project(),id); if(number(row,"version")!=version)throw conflict();
            if(!"SENT".equals(row.get("state")) && db.bind(id,version,cid)!=1)throw conflict();
            record("CID_RECOVERED",text(row,"caseNo"),actor,map("providerCid",cid,"reason",reason,"providerStatus",observed.status().providerCode()));
            return view(db.byId(project(),id));
        });
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public Map<String,Object> beginDispatch(long id,CregisExceptionOwnershipService.Evidence proof) {
        lockGate(); var initial=required(id); sources.ensureReserved(source(initial),"REFUND",text(initial,"caseNo"));
        var row=db.lockCase(project(),id); if(!"APPROVED".equals(row.get("state")))return null;
        checkLocks(source(row),number(row,"claimantUserId"));
        requireFresh(proof,row); requireDispatchSafe(row);
        if(db.intent(id,number(row,"version"))!=1)return null;
        record("DISPATCH_INTENT",text(row,"caseNo"),null,map("businessKey",row.get("businessKey"),"netAmount",row.get("netAmount")));
        return db.byId(project(),id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void dispatchBlocked(long id,RuntimeException failure) {
        String code=failure instanceof BizException&&failure.getMessage()!=null
                &&failure.getMessage().matches("(?:CREGIS_[A-Z0-9_]+|OBJECT_LOCKED_BY_A2)")
                ?failure.getMessage():"CREGIS_REFUND_VERIFICATION_UNAVAILABLE";
        lockGate();
        if(db.dispatchBlocked(project(),id,code)==1)
            record("DISPATCH_BLOCKED",Long.toString(id),null,map("reason",code));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void bindSubmission(long id,long cid) {
        var before=required(id); lockGate();sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
        var row=db.lockCase(project(),id);
        if(!Set.of("SUBMITTING","UNKNOWN").contains(text(row,"state")))return;
        if(db.bind(id,number(row,"version"),cid)!=1)throw conflict();
        record("SUBMITTED",text(row,"caseNo"),null,map("providerCid",cid));
    }
    @Transactional(rollbackFor=Exception.class)
    public void unknown(long id,String reason) {
        var row=db.lockCase(project(),id);
        if(row==null||!Set.of("SUBMITTING","SENT","UNKNOWN").contains(text(row,"state")))return;
        if(db.unknown(id,number(row,"version"),safe(reason))!=1)throw conflict();
        record("SUBMISSION_UNKNOWN",text(row,"caseNo"),null,map("reason",safe(reason)));
    }
    /** Late signed evidence revokes a replacement's unsent approval, never transfers money. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public void lateCallback(String caseNo) {
        lockGate();var old=db.byKey(project(),caseNo);
        if(old==null||!Set.of("FAILED_NO_TRANSFER","REPROPOSED").contains(text(old,"state")))return;
        var guard=db.guard(project(),text(old,"txid"),(int)number(old,"logIndex"));
        boolean restored=false;
        if(guard!=null&&"AVAILABLE".equals(guard.get("state"))) {
            sources.reserve(source(old),"REFUND",caseNo);restored=true;
        } else if(guard!=null&&"RESERVED".equals(guard.get("state"))&&"REFUND".equals(guard.get("kind"))) {
            String owner=text(guard,"ownerCase");
            if(caseNo.equals(owner)){sources.ensureReserved(source(old),"REFUND",caseNo);restored=true;}
            else {
                var replacement=db.byKey(project(),owner);
                if(replacement!=null&&Set.of("MAKER_DONE","APPROVED").contains(text(replacement,"state"))&&replacement.get("dispatchIntentAt")==null) {
                    sources.ensureReserved(source(old),"REFUND",owner);
                    if(db.state(number(replacement,"id"),number(replacement,"version"),text(replacement,"state"),"CANCELLED_LATE_EVIDENCE",null,"CREGIS_LATE_PROVIDER_EVIDENCE")!=1)throw conflict();
                    sources.release(source(old),"REFUND",owner);sources.reserve(source(old),"REFUND",caseNo);restored=true;
                    record("REPLACEMENT_CANCELLED",owner,null,map("lateEvidenceCase",caseNo));
                }
            }
        }
        if(!restored) {
            deposits.blockProvisionGateAny();
            deposits.insertRiskAlert(project(),"refund-late-"+caseNo,"P0","REFUND_LATE_EVIDENCE","Late evidence after another disposition: "+caseNo);
        }
        if(db.lateEvidence(caseNo)!=1)throw conflict();
        record("LATE_EVIDENCE_HOLD",caseNo,null,map("sourceRestored",restored));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void rejectedCreate(long id,String reason) {
        var before=required(id);lockGate();sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
        var row=db.lockCase(project(),id);if(!"SUBMITTING".equals(row.get("state")))return;
        if(db.state(id,number(row,"version"),"SUBMITTING","FAILED_NO_TRANSFER",null,safe(reason))!=1)throw conflict();
        record("FAILED_NO_TRANSFER",text(row,"caseNo"),null,map("reason",safe(reason)));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public void observed(long id,CregisGateway.PayoutOrder observed,BscDepositProof.Proof proof) {
        var before=required(id);lockGate();sources.ensureReserved(source(before),"REFUND",text(before,"caseNo"));
        var row=db.lockCase(project(),id); if(!Set.of("SENT","UNKNOWN").contains(text(row,"state")))return;
        validateObserved(row,observed,number(row,"providerCid"));
        int status=observed.status().providerCode();
        if(status==0||status==1||status==5)return;
        if(status==7||status!=6&&observed.txid()!=null||status!=6&&(db.successCallback(text(row,"caseNo"))>0||db.successProgressCount(text(row,"caseNo"))>0)) {
            if(db.unknown(id,number(row,"version"),"CREGIS_REFUND_AMBIGUOUS_PROVIDER_STATUS")!=1)throw conflict();return;
        }
        boolean complete=status==6;
        if(complete)db.successProgress(text(row,"caseNo"),observed.cid(),decimal(row,"netAmount"),observed.txid());
        if(complete&&(proof==null||proof.confirmations()<Math.max(15,transport.requiredConfirmations())))return;
        String next=complete?"COMPLETED":"FAILED_NO_TRANSFER";
        if(db.terminal(id,number(row,"version"),observed.cid(),next,observed.txid(),complete?null:"CREGIS_PREBROADCAST_REJECTED")!=1)throw conflict();
        if(db.ledger(text(row,"caseNo"),next,observed.cid(),decimal(row,"netAmount"),observed.txid(),encode(map("providerStatus",status,
                "proof",proof==null?Map.of():proof)))!=1)throw conflict();
        if(complete)sources.complete(source(row),"REFUND",text(row,"caseNo"),"REFUNDED");
        record(next,text(row,"caseNo"),null,map("providerCid",observed.cid(),"netAmount",row.get("netAmount"),"txid",observed.txid()));
    }
    public CregisGateway.PayoutOrder query(Map<String,Object> row,long cid) {
        var result=router.provider().queryPayout(new CregisGateway.PayoutQuery(cid,text(row,"businessKey"),text(row,"returnAddress"),decimal(row,"netAmount")));
        validateObserved(row,result,cid);return result;
    }
    private void validateObserved(Map<String,Object> row,CregisGateway.PayoutOrder result,long cid) {
        if(result==null||result.cid()!=cid||result.status()==null||!CregisConstants.BSC_CHAIN_ID.equals(result.chainId())
                ||!CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(result.tokenId())
                ||!CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(result.currency())
                ||!text(row,"businessKey").equals(result.thirdPartyId())||!text(row,"returnAddress").equalsIgnoreCase(result.address())
                ||result.amount()==null||decimal(row,"netAmount").compareTo(result.amount())!=0)throw new BizException(409,"CREGIS_REFUND_PROVIDER_CONFLICT");
    }
    public CregisExceptionOwnershipService.Evidence fresh(Map<String,Object> row) {
        var proof=ownership.preflight(new CregisExceptionOwnershipService.SourceRef(text(row,"sourceType"),number(row,"sourceId")));
        requireFresh(proof,row);return proof;
    }
    private void requireFresh(CregisExceptionOwnershipService.Evidence proof,Map<String,Object> row) {
        requireEligible(proof);
        if(proof.checkedAt().isBefore(Instant.now().minusSeconds(5))||!Objects.equals(proof.evidenceHash(),row.get("sourceEvidenceHash"))
                ||!proof.fromAddress().equalsIgnoreCase(text(row,"returnAddress"))||proof.source().grossAmount().compareTo(decimal(row,"grossAmount"))!=0
                ||!proof.source().txid().equals(text(row,"txid"))||proof.source().logIndex()!=number(row,"logIndex"))
            throw new BizException(409,"CREGIS_REFUND_EVIDENCE_CHANGED");
        ownership.requireConsumed(text(row,"ownershipReceipt"),text(row,"ownershipReference"),"REFUND_ORIGIN",null,proof.evidenceHash());
    }
    private void requireEligible(CregisExceptionOwnershipService.Evidence proof) {
        if(proof==null||proof.source().projectId()!=project()||!Set.of("DUST_HOLD","UNATTRIBUTED_HOLD").contains(proof.sourceStatus())
                ||proof.confirmations()<Math.max(15,transport.requiredConfirmations())||proof.fromAddress()==null
                ||!proof.fromAddress().matches("(?i)0x[0-9a-f]{40}"))throw new BizException(409,"CREGIS_REFUND_SOURCE_NOT_ELIGIBLE");
    }
    private void requireDispatchSafe(Map<String,Object> row) {
        var gate=deposits.lockProvisionGate();String reason=gate==null?"":text(gate,"switchReason");
        if(!transport.configured()||gate==null||"BUSY".equals(gate.get("state"))||reason.startsWith("EMERGENCY:")
                ||db.otherCriticalAlerts(project(),number(row,"depositCid"))!=0||db.criticalDeposits(project())!=0
                ||deposits.disputedDeliveryCountForProject(project())!=0||db.otherActiveRefunds(project(),number(row,"id"))!=0)
            throw new BizException(409,"CREGIS_REFUND_DISPATCH_RISK_HOLD");
        if(db.otherUnresolved(project(),number(row,"depositCid"),text(row,"txid"),(int)number(row,"logIndex"),
                text(row,"depositAddress"),decimal(row,"grossAmount"))!=0)
            throw new BizException(409,"CREGIS_REFUND_DISPATCH_RISK_HOLD");
    }
    private void lockGate() { if(deposits.lockProvisionGate()==null)throw new BizException(503,"CREGIS_RISK_GATE_MISSING"); }
    private void checkLocks(CregisExceptionSourceService.Source source,Long user) {
        if(!A2ReplayContext.isReplaying()&&(locks.countActiveByTarget("D","deposit","CR-"+source.cid())>0
                ||user!=null&&user>0&&locks.countActiveByTarget("C","user",Long.toString(user))>0))
            throw new BizException(409,"OBJECT_LOCKED_BY_A2");
    }
    private CregisExceptionSourceService.Source source(Map<String,Object> row) {
        return new CregisExceptionSourceService.Source(project(),number(row,"depositCid"),text(row,"txid"),(int)number(row,"logIndex"),text(row,"depositAddress"),decimal(row,"grossAmount"));
    }
    private Map<String,Object> required(long id) { var row=db.byId(project(),id);if(row==null)throw new BizException(404,"CREGIS_REFUND_NOT_FOUND");return row; }
    private void requireRow(Map<String,Object> row,long version,String state) {if(row==null||number(row,"projectId")!=project()||number(row,"version")!=version||!state.equals(row.get("state")))throw conflict();}
    private void different(Map<String,Object> row,long actor) {
        if(number(row,"makerId")==actor)throw new BizException(403,"CREGIS_REFUND_DIFFERENT_CHECKER_REQUIRED");
        if(!admins.isActiveAdministrator(number(row,"makerId")))throw new BizException(403,"CREGIS_REFUND_MAKER_INACTIVE");
    }
    private void decision(String decision,boolean cancel) {if(decision==null||!("APPROVE".equals(decision)||"REJECT".equals(decision)||cancel&&"CANCEL".equals(decision)))throw invalid();}
    private long project() {if(config.getMode()!=CregisProperties.Mode.PROVIDER||config.getProjectId()<=0)throw new BizException(503,"CREGIS_PROVIDER_DISABLED");return config.getProjectId();}
    private long admin(boolean write) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getDetails() instanceof Map<?,?> d)||!"ADMIN".equals(d.get("subjectType"))
                ||!auth.getAuthorities().stream().anyMatch(a->"finance_d1_read".equals(a.getAuthority()))
                ||write&&(!auth.getAuthorities().stream().anyMatch(a->"finance_d1_channel_manage".equals(a.getAuthority()))
                ||!auth.getAuthorities().stream().anyMatch(a->"finance_d2_withdrawal_approve".equals(a.getAuthority()))))throw new BizException(403,"CREGIS_REFUND_ADMIN_REQUIRED");
        long id=positiveId(String.valueOf(auth.getPrincipal()));if(!admins.isActiveAdministrator(id))throw new BizException(403,"CREGIS_REFUND_ADMIN_REQUIRED");return id;
    }
    private Map<String,Object> command(long actor,String key,Object request,Supplier<Map<String,Object>> action) {
        return idempotency.executeRetained("CREGIS_REFUND:"+actor,key,sha(encode(request)),Map.class,action::get);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> replay(long actor,String key,Object request) {
        var receipt=idempotency.recoveryResult("CREGIS_REFUND:"+actor,key,sha(encode(request)),Map.class);
        if(receipt.status()==AdminIdempotencyService.RecoveryStatus.SUCCEEDED)return receipt.response();
        if(receipt.status()!=AdminIdempotencyService.RecoveryStatus.NOT_FOUND&&receipt.status()!=AdminIdempotencyService.RecoveryStatus.FAILED)
            throw new BizException(409,"CREGIS_REFUND_IDEMPOTENCY_CONFLICT");
        return null;
    }
    private String snapshotHash(CregisExceptionOwnershipService.Evidence proof,Map<String,Object> policy,BigDecimal fee,BigDecimal net) {
        return sha(proof.evidenceHash()+"|"+policy.get("version")+"|"+policy.get("mode")+"|"+fee.toPlainString()+"|"+net.toPlainString());
    }
    private Map<String,Object> view(Map<String,Object> row) {
        if(row==null)throw conflict();var result=new LinkedHashMap<>(row);result.remove("ownershipReceipt");result.remove("ownershipReference");result.remove("sourceEvidenceHash");result.remove("businessKey");
        for(String field:List.of("id","projectId","sourceId","claimantUserId","depositCid","makerId","checkerId","providerCid"))
            if(result.get(field)!=null)result.put(field,result.get(field).toString());
        result.replaceAll((k,v)->v instanceof BigDecimal amount?amount.toPlainString():v);
        return result;
    }
    private void record(String action,String no,Long actor,Map<String,Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_EXCEPTION_REFUND_"+action).resourceType("CREGIS_EXCEPTION_REFUND")
                .resourceId(no).bizNo(no).actorType(actor==null?"SYSTEM":"ADMIN").actorId(actor)
                .actorUsername(actor==null?"cregis-refund-worker":"admin:"+actor).riskLevel("CRITICAL").result("SUCCESS").detail(detail).build());
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("CREGIS_REFUND_SERIALIZATION_FAILED",failure);}}
    static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    static long number(Map<String,Object> row,String key) {Object value=row.get(key);return value==null?0:value instanceof Number n?n.longValue():Long.parseLong(value.toString());}
    static String text(Map<String,Object> row,String key) {Object value=row.get(key);return value==null?"":value.toString();}
    static BigDecimal decimal(Map<String,Object> row,String key) {return new BigDecimal(row.get(key).toString());}
    static Map<String,Object> map(Object... pairs) {Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put(pairs[i].toString(),pairs[i+1]);return map;}
    static long positiveId(String value) {try{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(value);}catch(NumberFormatException invalid){throw invalid();}}
    private static BigDecimal amount(String value) {try{BigDecimal amount=new BigDecimal(value);if(amount.signum()<0||amount.scale()>6||amount.precision()-amount.scale()>12)throw invalid();return amount.setScale(6);}catch(NumberFormatException|NullPointerException invalid){throw invalid();}}
    private static void requireReason(String reason) {if(reason==null||reason.trim().length()<10||reason.length()>300)throw invalid();}
    private static String safe(String reason) {return reason==null?"CREGIS_REFUND_UNKNOWN":reason.substring(0,Math.min(120,reason.length()));}
    private static BizException invalid() {return new BizException(422,"CREGIS_REFUND_REQUEST_INVALID");}
    private static BizException conflict() {return new BizException(409,"CREGIS_REFUND_STATE_CONFLICT");}
}
