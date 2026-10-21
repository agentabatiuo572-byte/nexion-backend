package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.CregisExceptionClaimMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class CregisExceptionClaimService {
    private final CregisProperties config;
    private final CregisExceptionOwnershipService ownership;
    private final CregisExceptionSourceService sources;
    private final CregisExceptionAdminGuard admins;
    private final CregisExceptionClaimMapper db;
    private final CregisDepositMapper deposits;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final TreasuryLedgerRepository treasury;
    private final SupportPaymentAttributionFacade attribution;
    private final AuditObjectLockMapper locks;

    public Map<String,Object> page(long before,int limit) {
        admins.require(false);
        if(before<0 || limit<1 || limit>100)throw fail("CREGIS_EXCEPTION_REQUEST_INVALID");
        var rows=db.page(config.getProjectId(),before,limit+1);
        boolean more=rows.size()>limit;
        var records=rows.stream().limit(limit).map(this::view).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",records);result.put("hasMore",more);
        result.put("nextBeforeId",more?String.valueOf(rows.get(limit-1).get("id")):null);return result;
    }
    public Map<String,Object> preview(String challenge,String signature,long target) {
        admins.require(false);
        var verified=ownership.verify(challenge,signature,"CLAIM_CREDIT",target);
        requireClaimable(verified.evidence());
        return preview(verified.evidence(),target);
    }
    public Map<String,Object> propose(String key,String challenge,String signature,long target,String evidenceHash,String reason) {
        long actor=admins.require(true);reason(reason);
        String hash=CregisExceptionOwnershipService.sha(challenge+"|"+signature+"|"+target+"|"+evidenceHash+"|"+reason.trim());
        String scope="CREGIS_CLAIM_PROPOSE:"+actor;
        var prior=idempotency.recoveryResult(scope,key,hash,Map.class);
        if(prior.status()==AdminIdempotencyService.RecoveryStatus.SUCCEEDED)return prior.response();
        if(prior.status()!=AdminIdempotencyService.RecoveryStatus.NOT_FOUND && prior.status()!=AdminIdempotencyService.RecoveryStatus.FAILED)throw fail("CREGIS_EXCEPTION_COMMAND_PENDING_OR_CONFLICT");
        var verified=ownership.verify(challenge,signature,"CLAIM_CREDIT",target);
        var evidence=verified.evidence();requireClaimable(evidence);
        if(!evidence.evidenceHash().equals(evidenceHash))throw fail("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
        String caseNo="CL-"+challenge.replace("-","");
        return idempotency.executeRetained(scope,key,hash,Map.class,()->{
            gate(evidence);ownership.requireFresh(evidence);
            sources.reserve(evidence.source(),"CLAIM",caseNo);lockObservation(evidence);requireUnlocked(evidence.source().cid(),target);
            ownership.consume(verified,caseNo);
            var row=new LinkedHashMap<String,Object>();row.put("caseNo",caseNo);row.put("projectId",config.getProjectId());
            row.put("sourceType",evidence.ref().type());row.put("sourceId",evidence.ref().id());row.put("cid",evidence.source().cid());
            row.put("txid",evidence.source().txid());row.put("logIndex",evidence.source().logIndex());row.put("address",evidence.source().address());
            row.put("fromAddress",evidence.fromAddress());row.put("grossAmount",evidence.source().grossAmount());row.put("targetUserId",target);
            row.put("challengeId",challenge);row.put("evidenceHash",evidenceHash);row.put("makerId",actor);row.put("reason",reason.trim());
            if(db.insertCase(row)!=1 || db.lastId()==null)throw fail("CREGIS_EXCEPTION_CASE_WRITE_FAILED");
            long id=db.lastId();writeAudit("CREGIS_CLAIM_PROPOSED",caseNo,actor,target,Map.of("source",evidenceHash,"reason",reason.trim()));
            return Map.of("caseId",Long.toString(id),"caseNo",caseNo,"state","MAKER_DONE","version",0);
        });
    }
    public Map<String,Object> decide(String key,long id,long version,String decision,String reason) {
        long actor=admins.require(true);reason(reason);
        if(id<=0 || version<0 || !List.of("APPROVE","REJECT").contains(String.valueOf(decision)))throw fail("CREGIS_EXCEPTION_REQUEST_INVALID");
        String hash=CregisExceptionOwnershipService.sha(id+"|"+version+"|"+decision+"|"+reason.trim());String scope="CREGIS_CLAIM_DECIDE:"+actor;
        var prior=idempotency.recoveryResult(scope,key,hash,Map.class);
        if(prior.status()==AdminIdempotencyService.RecoveryStatus.SUCCEEDED)return prior.response();
        if(prior.status()!=AdminIdempotencyService.RecoveryStatus.NOT_FOUND && prior.status()!=AdminIdempotencyService.RecoveryStatus.FAILED)throw fail("CREGIS_EXCEPTION_COMMAND_PENDING_OR_CONFLICT");
        var snapshot=db.snapshot(config.getProjectId(),id);requireCase(snapshot,actor,version);
        CregisExceptionOwnershipService.Evidence evidence="APPROVE".equals(decision)?ownership.preflight(new CregisExceptionOwnershipService.SourceRef(
                (String)snapshot.get("source_type"),((Number)snapshot.get("source_id")).longValue())):null;
        if(evidence!=null && !evidence.evidenceHash().equals(snapshot.get("evidence_hash")))throw fail("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
        return idempotency.executeRetained(scope,key,hash,Map.class,()->{
            if(deposits.lockProvisionGate()==null)throw fail("CREGIS_DEPOSIT_RISK_GATE_MISSING");
            var row=db.lock(config.getProjectId(),id);requireCase(row,actor,version);
            var source=source(row);String caseNo=(String)row.get("case_no");sources.ensureReserved(source,"CLAIM",caseNo);
            long target=((Number)row.get("target_user_id")).longValue();
            requireUnlocked(source.cid(),target);
            if("APPROVE".equals(decision)) {
                admins.requireActive(((Number)row.get("maker_id")).longValue());
                requireClaimable(evidence);gate(evidence);ownership.requireFresh(evidence);lockObservation(evidence);
                if(!evidence.evidenceHash().equals(row.get("evidence_hash")))throw fail("CREGIS_EXCEPTION_EVIDENCE_CHANGED");
                ownership.requireConsumed((String)row.get("challenge_id"),caseNo,"CLAIM_CREDIT",target,evidence.evidenceHash());
                credit(evidence,target,caseNo);
            } else sources.release(source,"CLAIM",caseNo);
            String state="APPROVE".equals(decision)?"CREDITED":"REJECTED";
            if(db.decide(config.getProjectId(),id,version,actor,state,reason.trim())!=1)throw fail("CREGIS_EXCEPTION_VERSION_CHANGED");
            writeAudit("CREGIS_CLAIM_"+decision,caseNo,actor,target,Map.of("evidenceHash",row.get("evidence_hash"),"reason",reason.trim()));
            return Map.of("caseId",Long.toString(id),"caseNo",caseNo,"state",state,"version",version+1);
        });
    }
    private void credit(CregisExceptionOwnershipService.Evidence e,long target,String reference) {
        if(!Long.valueOf(target).equals(deposits.lockActiveUser(target)))throw fail("CREGIS_EXCEPTION_TARGET_INVALID");
        ownership.requireTargetActive(target);
        var s=e.source();BigDecimal net=s.grossAmount().subtract(BigDecimal.ONE);String bizNo="CR-"+s.cid();
        var prepared=attribution.prepare(target,SupportPaymentFacts.Source.DEPOSIT_ORDER,bizNo,Long.toString(config.getProjectId()));
        if(deposits.insertEvent(target,config.getProjectId(),s.cid(),s.txid(),s.logIndex(),s.address(),s.grossAmount(),BigDecimal.ONE,net,
                e.blockNumber(),e.blockHash(),e.confirmations(),"CREDITED")!=1)throw fail("CREGIS_EVENT_INSERT_FAILED");
        if(deposits.recordTransferSender(config.getProjectId(),s.cid(),e.fromAddress())!=1)throw fail("CREGIS_TRANSFER_SENDER_SNAPSHOT_FAILED");
        var wallet=deposits.lockWallet(target);if(wallet==null)throw fail("CREGIS_WALLET_MISSING");
        ownership.requireFresh(e);attribution.beforeCash(prepared);
        if(deposits.creditWallet(net,target,((Number)wallet.get("version")).longValue())!=1)throw fail("CREGIS_WALLET_CONFLICT");
        BigDecimal after=((BigDecimal)wallet.get("usdtAvailable")).add(net);
        if(attribution.insertLedger(prepared,net,after,"Verified ownership claim "+reference)!=1)throw fail("CREGIS_LEDGER_INSERT_FAILED");
        treasury.recordTopupReserve(bizNo,net,"CREGIS:"+s.cid());
        Long ledgerId=deposits.ledgerId(bizNo),eventId=deposits.eventId(config.getProjectId(),s.cid());
        if(ledgerId==null || eventId==null || deposits.linkLedger(ledgerId,eventId)!=1
                || deposits.insertDepositOrder(target,bizNo,s.txid(),s.logIndex(),net,e.confirmations(),ledgerId)!=1)throw fail("CREGIS_LEDGER_LINK_FAILED");
        attribution.record(prepared);sources.complete(s,"CLAIM",reference,"CREDITED");ownership.requireFresh(e);
    }
    private void gate(CregisExceptionOwnershipService.Evidence e) {
        var gate=deposits.lockProvisionGate();
        boolean enabled=gate!=null && (Boolean.TRUE.equals(gate.get("creditEnabled"))
                || gate.get("creditEnabled") instanceof Number n && n.intValue()==1);
        boolean automatic=gate!=null && "IDLE".equals(gate.get("state")) && enabled;
        boolean repair=gate!=null && "BLOCKED".equals(gate.get("state"))
                && ("CREGIS_UNRESOLVED_EXPOSURE_500".equals(gate.get("switchReason"))
                || "CREGIS_DEPOSIT_INCIDENT".equals(gate.get("switchReason")) && "UNATTRIBUTED_HOLD".equals(e.sourceStatus()));
        if(!config.isDepositCreditEnabled() || (!automatic && !repair)
                || db.otherCritical(config.getProjectId(),e.source().cid())!=0
                || deposits.disputedDeliveryCountForProject(config.getProjectId())!=0
                || deposits.criticalHeldDepositCount(config.getProjectId())!=0
                || db.otherPending(e.source().cid(),e.source().txid(),e.source().address(),e.source().grossAmount())!=0)
            throw fail("CREGIS_EXCEPTION_RISK_GATE_BLOCKED");
    }
    private void requireUnlocked(long cid,long user) {
        if(!A2ReplayContext.isReplaying() && (locks.countActiveByTarget("D","deposit","CR-"+cid)>0
                || locks.countActiveByTarget("C","user",Long.toString(user))>0))throw fail("CREGIS_EXCEPTION_OBJECT_LOCKED_BY_A2");
    }
    private void lockObservation(CregisExceptionOwnershipService.Evidence e) {
        var row=db.lockObservation(config.getProjectId(),e.ref().id());
        if(row==null || !"UNATTRIBUTED_HOLD".equals(row.get("status")) || !e.source().txid().equalsIgnoreCase((String)row.get("txid"))
                || e.source().logIndex()!=((Number)row.get("logIndex")).intValue()
                || !e.source().address().equalsIgnoreCase((String)row.get("address"))
                || e.source().grossAmount().compareTo(new BigDecimal(String.valueOf(row.get("grossAmount"))))!=0
                || e.blockNumber()!=((Number)row.get("blockNumber")).longValue())throw fail("CREGIS_EXCEPTION_SOURCE_CHANGED");
    }
    private void requireClaimable(CregisExceptionOwnershipService.Evidence e) {
        if(e==null || !"OBSERVATION".equals(e.ref().type()) || !"UNATTRIBUTED_HOLD".equals(e.sourceStatus())
                || e.source().grossAmount().compareTo(BigDecimal.ONE)<=0)throw fail("CREGIS_EXCEPTION_CLAIM_NOT_ALLOWED");
    }
    private void requireCase(Map<String,Object> row,long actor,long version) {
        if(row==null || !"MAKER_DONE".equals(row.get("state")) || ((Number)row.get("version")).longValue()!=version)throw fail("CREGIS_EXCEPTION_VERSION_CHANGED");
        if(((Number)row.get("maker_id")).longValue()==actor)throw new BizException(403,"CREGIS_EXCEPTION_DIFFERENT_CHECKER_REQUIRED");
    }
    private CregisExceptionSourceService.Source source(Map<String,Object> r) {return new CregisExceptionSourceService.Source(config.getProjectId(),((Number)r.get("cid")).longValue(),(String)r.get("txid"),((Number)r.get("log_index")).intValue(),(String)r.get("address"),(BigDecimal)r.get("gross_amount"));}
    private Map<String,Object> preview(CregisExceptionOwnershipService.Evidence e,long target) {
        var s=e.source();return Map.ofEntries(Map.entry("sourceType",e.ref().type()),Map.entry("sourceId",Long.toString(e.ref().id())),
                Map.entry("targetUserId",Long.toString(target)),Map.entry("cid",Long.toString(s.cid())),Map.entry("txid",s.txid()),Map.entry("logIndex",s.logIndex()),
                Map.entry("address",s.address()),Map.entry("fromAddress",e.fromAddress()),Map.entry("grossAmount",s.grossAmount().toPlainString()),
                Map.entry("feeAmount","1.000000"),Map.entry("netAmount",s.grossAmount().subtract(BigDecimal.ONE).toPlainString()),
                Map.entry("evidenceHash",e.evidenceHash()),Map.entry("confirmations",e.confirmations()),Map.entry("checkedAt",e.checkedAt().toString()));
    }
    private Map<String,Object> view(Map<String,Object> r) {
        var v=new LinkedHashMap<String,Object>();
        for(var entry:r.entrySet()) {
            String k=entry.getKey();Object value=entry.getValue();
            String camel=java.util.regex.Pattern.compile("_([a-z])").matcher(k).replaceAll(m->m.group(1).toUpperCase(java.util.Locale.ROOT));
            if(value!=null && (k.equals("id") || k.endsWith("_id") || k.equals("cid")))value=String.valueOf(value);
            if(value instanceof BigDecimal n)value=n.toPlainString();v.put(camel,value);
        }
        if(r.get("gross_amount") instanceof BigDecimal gross) {
            v.put("feeAmount","1.000000");
            v.put("netAmount",gross.subtract(BigDecimal.ONE).setScale(6).toPlainString());
        }
        v.put("caseId",String.valueOf(r.get("id")));return v;
    }
    private void writeAudit(String action,String ref,long actor,long target,Map<String,Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("CREGIS_EXCEPTION_CLAIM").resourceId(ref).bizNo(ref)
                .actorType("ADMIN").actorId(actor).actorUsername(AdminActorResolver.resolve(null)).userId(target).riskLevel("CRITICAL").result("SUCCESS").detail(detail).build());
    }
    private void reason(String reason){if(reason==null || reason.trim().length()<10 || reason.trim().length()>300)throw fail("CREGIS_EXCEPTION_REASON_REQUIRED");}
    private BizException fail(String message){return new BizException(409,message);}
}
