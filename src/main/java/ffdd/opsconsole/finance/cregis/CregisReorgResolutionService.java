package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.CregisReorgResolutionMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit compensating operations. Never resubmits a provider payment or changes ordinary switches. */
@Service
@RequiredArgsConstructor
public class CregisReorgResolutionService {
    public enum Action { REVERSE, RESTORE, RELEASE_HOLD, COLLECT_DEBT, UNFREEZE }
    private final CregisReorgResolutionMapper db;
    private final CregisProperties config;
    private final BscDepositProof chain;
    private final CregisExceptionSourceService sources;
    private final CregisGatewayRouter router;
    private final SupportFundJournalFacade funds;
    private final SupportFundJournalMapper journals;
    private final TreasuryLedgerRepository treasury;
    private final AuditLogService audit;
    private final AdminOperatorRoleResolver admins;
    private final AdminIdempotencyService idempotency;
    private final AuditObjectLockMapper locks;
    private final FinanceWithdrawalControlFacade withdrawalControl;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper json;
    public record HoldBefore(SupportFundJournalFacade.Before binding, Map<String,Object> wallet, long userId) {}
    public record Result(String id,String status,long version) { public Result(long id,String status,long version){this(String.valueOf(id),status,version);} }
    public record Proposal(Action action,long expectedVersion,String reason,String evidenceHash) {}
    public record Decision(String decision,long expectedVersion,String reason) {}
    private record Evidence(Map<String,Object> row,Map<String,Object> summary,String hash,
                            BscDepositProof.ReorgProof chain,Instant checkedAt) {}

    @Transactional(propagation=Propagation.MANDATORY)
    public HoldBefore beforeIncident(long userId) {
        if(db.lockUser(userId)==null)throw conflict("USER_MISSING");
        var binding=funds.begin(List.of(userId));
        return new HoldBefore(binding,required(db.wallet(userId)),userId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recordIncident(long eventId,String kind,BigDecimal held,HoldBefore before) {
        if(before==null)throw conflict("HOLD_CAPTURE_MISSING");
        var legacy=required(db.legacyIncident(eventId,kind));
        long incident=id(legacy,"id");
        if(db.register(incident)!=1)throw conflict("INCIDENT_REGISTER_CONFLICT");
        var row=required(db.lockIncident(config.getProjectId(),incident));
        if(id(row,"userId")!=before.userId() || money(row,"heldAmount").compareTo(held)!=0)throw conflict("HOLD_SOURCE_MISMATCH");
        var events=new ArrayList<Event>();
        if(held.signum()>0)events.add(new Event(operationNo(incident,0)+":HOLD",ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.MOVE,
                available(before.userId()),risk(before.userId(),incident),null,held,null));
        append(row,"HOLD",0,before,events,Map.of("kind",kind,"held",held),"NONE");
        audit("HOLD",incident,0,Map.of("heldAmount",held,"eventId",eventId));
    }

    public Map<String,Object> incidents(long before,int limit) {
        requireAdmin(false); validatePage(before,limit);
        var rows=db.incidents(config.getProjectId(),before,limit+1);
        var records=rows.stream().limit(limit).map(row->{var value=transport(row);value.put("allowedActions",allowed(row));return value;}).toList();
        return page(records,rows.size()>limit);
    }
    public Map<String,Object> proposals(long before,int limit) {
        requireAdmin(false);validatePage(before,limit);
        var rows=db.proposals(config.getProjectId(),before,limit+1);
        return page(rows.stream().limit(limit).map(CregisReorgResolutionService::transport).toList(),rows.size()>limit);
    }
    public Map<String,Object> evidence(long incident,Action action) {
        requireAdmin(false);var evidence=inspect(incident,action);
        var value=transport(evidence.summary());value.put("evidenceHash",evidence.hash());return value;
    }
    public Result propose(long incident,String key,Proposal request) {
        long actor=requireAdmin(true);reason(request==null?null:request.reason());
        if(request.action()==null || request.expectedVersion()<0 || request.evidenceHash()==null
                || !request.evidenceHash().matches("[0-9a-fA-F]{64}"))throw conflict("REQUEST_INVALID");
        return idempotency.executeRetained("CREGIS_REORG_PROPOSE:"+actor,key,sha(incident+"|"+request),Result.class,()-> {
            var proof=inspect(incident,request.action());
            if(id(proof.row(),"version")!=request.expectedVersion() || !proof.hash().equalsIgnoreCase(request.evidenceHash()))throw conflict("EVIDENCE_CHANGED");
            return new TransactionTemplate(txManager).execute(ignored->{
                lockSubject(proof.row());var row=required(db.lockIncident(config.getProjectId(),incident));
                if(id(row,"version")!=request.expectedVersion())throw conflict("VERSION_CHANGED");
                var proposal=new LinkedHashMap<String,Object>();proposal.put("incident",incident);proposal.put("action",request.action().name());
                proposal.put("version",request.expectedVersion());proposal.put("hash",proof.hash());proposal.put("reason",request.reason().trim());proposal.put("maker",actor);
                if(db.propose(proposal)!=1)throw conflict("PROPOSAL_CONFLICT");fresh(proof);
                long caseId=id(proposal,"id");audit("PROPOSE",incident,actor,Map.of("caseId",caseId,"action",request.action(),"evidenceHash",proof.hash()));
                return new Result(caseId,"MAKER_DONE",0);
            });
        });
    }
    public Result decide(long caseId,String key,Decision request) {
        long actor=requireAdmin(true);reason(request==null?null:request.reason());
        if(request.decision()==null || !Set.of("APPROVE","REJECT").contains(request.decision()) || request.expectedVersion()<0)throw conflict("REQUEST_INVALID");
        return idempotency.executeRetained("CREGIS_REORG_DECIDE:"+actor,key,sha(caseId+"|"+request),Result.class,()-> {
            var proposal=required(db.proposal(caseId));checkProposal(proposal,actor,request.expectedVersion());
            long incident=id(proposal,"incidentId");Action action=Action.valueOf(text(proposal,"action"));
            Evidence evidence="APPROVE".equals(request.decision())?inspect(incident,action):null;
            if(evidence!=null && !evidence.hash().equals(proposal.get("evidenceHash")))throw conflict("EVIDENCE_CHANGED");
            return new TransactionTemplate(txManager).execute(ignored->{
                var initial=required(db.incident(config.getProjectId(),incident));lockSubject(initial);
                var lockedProposal=required(db.lockProposal(caseId));checkProposal(lockedProposal,actor,request.expectedVersion());
                var row=required(db.lockIncident(config.getProjectId(),incident));
                if(id(row,"version")!=id(lockedProposal,"expectedVersion"))throw conflict("VERSION_CHANGED");
                if(evidence!=null) {
                    if(!fingerprint(row).equals(fingerprint(evidence.row())))throw conflict("EVIDENCE_CHANGED");
                    apply(row,action,evidence,actor);fresh(evidence);
                }
                String status=evidence==null?"REJECTED":"RESOLVED";
                if(db.decide(caseId,request.expectedVersion(),actor,status)!=1)throw conflict("VERSION_CHANGED");
                audit("DECIDE_"+request.decision(),incident,actor,Map.of("caseId",caseId,"action",action,"reason",request.reason().trim(),"makerId",id(proposal,"makerId")));
                return new Result(incident,status,id(row,"version")+(evidence==null?0:1));
            });
        });
    }
    private void apply(Map<String,Object> row,Action action,Evidence evidence,long actor) {
        long incident=id(row,"id"),user=id(row,"userId"),version=id(row,"version");
        var wallet=required(db.wallet(user));
        if(!walletFingerprint(wallet).equals(evidence.summary().get("walletFingerprint")))throw conflict("WALLET_CHANGED");
        var before=new HoldBefore(funds.begin(List.of(user)),wallet,user);
        BigDecimal net=money(row,"netAmount"),held=money(row,"heldAmount"),recovered=money(row,"recoveredAmount"),debt=money(row,"outstandingDebt");
        BigDecimal availableDelta=BigDecimal.ZERO,riskDelta=BigDecimal.ZERO,cumulativeDelta=BigDecimal.ZERO;
        String state=text(row,"status"),effect="NONE";var events=new ArrayList<Event>();
        String no=operationNo(incident,version+1),origin=depositOrigin(row);
        switch(action) {
            case REVERSE -> {
                BigDecimal extra=money(wallet,"usdtAvailable").min(net.subtract(held));
                recovered=held.add(extra);debt=net.subtract(recovered);availableDelta=extra.negate();riskDelta=held.negate();cumulativeDelta=net.negate();
                if(held.signum()>0)events.add(debit(no+":HELD",risk(user,incident),origin,held));
                if(extra.signum()>0)events.add(debit(no+":AVAILABLE",available(user),origin,extra));
                events.add(new Event(no+":REVOKE",ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.REVOKE_DEPOSIT,null,null,origin,net,null));
                held=BigDecimal.ZERO;state=debt.signum()>0?"REVERSED_DEBT":"REVERSED";effect="REVERSE";
                treasury.reverseTopupReserve("CR-"+id(row,"cid")+":REORG:"+incident,net,no);
                changeSource(row,"REORG_REVERSED",null);
            }
            case RELEASE_HOLD -> {
                availableDelta=held;riskDelta=held.negate();
                if(held.signum()>0)events.add(new Event(no+":RELEASE",ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.MOVE,
                        risk(user,incident),available(user),null,held,null));
                held=BigDecimal.ZERO;state="RELEASED";changeSource(row,"CREDITED",evidence.chain().canonical());
            }
            case RESTORE -> {
                availableDelta=net.subtract(debt);cumulativeDelta=net;
                events.add(new Event(no+":REINSTATE",ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.RESTORE_DEPOSIT,null,null,origin,net,null));
                for(var operation:db.operations(incident)) {
                    for(Event event:decodeEvents(text(operation,"eventsJson")))
                        if(event.action()==ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.REORG_DEBIT)
                            events.add(new Event(no+":RETURN:"+events.size(),ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.REORG_REFUND,
                                    null,available(user),event.id(),event.amount(),null));
                }
                if(recovered.compareTo(availableDelta)!=0)throw conflict("RECOVERY_TOTAL_MISMATCH");
                debt=BigDecimal.ZERO;state="RESTORED";effect="RESTORE";
                treasury.recordTopupReserve("CR-"+id(row,"cid")+":REORG-RESTORE:"+incident,net,no);
                changeSource(row,"CREDITED",evidence.chain().canonical());
            }
            case COLLECT_DEBT -> {
                BigDecimal collection=money(wallet,"usdtAvailable").min(debt);
                if(collection.signum()<=0)throw conflict("NO_AVAILABLE_RECOVERY");
                availableDelta=collection.negate();debt=debt.subtract(collection);recovered=recovered.add(collection);
                events.add(debit(no+":COLLECT",available(user),origin,collection));
                state=debt.signum()>0?"REVERSED_DEBT":"REVERSED";
            }
            case UNFREEZE -> {
                if(db.openForUser(user)!=0)throw conflict("OPEN_INCIDENT_OR_DEBT");
                var account=required(db.lockUser(user));
                if(!"CREGIS_DEPOSIT_INCIDENT".equals(account.get("freezeSource")) || !"FROZEN".equals(account.get("status")))throw conflict("OTHER_FREEZE_SOURCE");
                if(db.unfreeze(user,text(account,"freezeRef"))!=1)throw conflict("FREEZE_SOURCE_CHANGED");
                withdrawalControl.restoreWithdrawalsFrozenByUserStatus(user,"Cregis resolved incident "+incident,"admin:"+actor);
            }
        }
        if(availableDelta.signum()!=0 || riskDelta.signum()!=0 || cumulativeDelta.signum()!=0) {
            if(db.walletChange(user,id(wallet,"version"),availableDelta,riskDelta,cumulativeDelta)!=1)throw conflict("WALLET_CONFLICT");
            if(availableDelta.signum()!=0)treasury.postLedgerEntry(no+":AVAILABLE",user,"CREGIS_REORG_"+action,"USDT",
                    availableDelta.signum()>0?"IN":"OUT",availableDelta.abs(),"SUCCESS","Cregis incident "+incident+" "+action);
        }
        if(db.update(incident,version,state,held,recovered,debt)!=1)throw conflict("VERSION_CHANGED");
        var after=required(db.incident(config.getProjectId(),incident));
        append(after,action.name(),version+1,before,events,evidence.summary(),effect);
    }

    private Evidence inspect(long incident,Action action) {
        if(action==null || config.getMode()!=CregisProperties.Mode.PROVIDER)throw conflict("REQUEST_INVALID");
        var row=required(db.incident(config.getProjectId(),incident));
        validateFinancialState(row);
        if(!allowed(row).contains(action.name()))throw conflict("ACTION_NOT_ALLOWED");
        BscDepositProof.ReorgProof proof;
        try { proof=chain.inspectCanonical(text(row,"txid"),(int)id(row,"logIndex"),text(row,"address"),money(row,"grossAmount"),id(row,"blockNumber"),text(row,"blockHash"),(String)row.get("transferFrom")); }
        catch(RuntimeException unavailable) {throw new BizException(503,"CREGIS_REORG_PROOF_UNAVAILABLE");}
        if((action==Action.REVERSE || action==Action.COLLECT_DEBT) && !proof.positivelyVerifiedAbsent())throw conflict("ABSENCE_NOT_PROVEN");
        if(action==Action.RESTORE || action==Action.RELEASE_HOLD) {
            if(proof.canonical()==null || proof.canonical().confirmations()<Math.max(15,config.getDepositConfirmations()))throw conflict("CANONICAL_PROOF_REQUIRED");
            var trades=router.provider().depositsByTxid(text(row,"txid"));
            if(trades.size()!=1 || trades.get(0).cid()!=id(row,"cid") || trades.get(0).status()!=1
                    || !CregisConstants.BSC_CHAIN_ID.equals(trades.get(0).chainId())
                    || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(trades.get(0).tokenId())
                    || !text(row,"txid").equalsIgnoreCase(trades.get(0).txid())
                    || !text(row,"address").equalsIgnoreCase(trades.get(0).address())
                    || trades.get(0).amount().compareTo(money(row,"grossAmount"))!=0)throw conflict("PROVIDER_CONFLICT");
        }
        var wallet=required(db.wallet(id(row,"userId")));
        var summary=new LinkedHashMap<String,Object>();summary.put("incidentId",incident);summary.put("action",action.name());summary.put("version",id(row,"version"));
        summary.put("financialSnapshot",fingerprint(row));summary.put("originalPosition",List.of(id(row,"logIndex"),id(row,"blockNumber"),text(row,"blockHash")));
        summary.put("originalLogIndex",row.get("originalLogIndex"));summary.put("originalSender",row.get("transferFrom"));summary.put("walletFingerprint",walletFingerprint(wallet));
        summary.put("positivelyVerifiedAbsent",proof.positivelyVerifiedAbsent());summary.put("originalCanonicalHash",proof.originalCanonicalHash());
        if(proof.canonical()!=null) {
            var canonical=proof.canonical();
            summary.put("canonicalIdentity",List.of(canonical.logIndex(),canonical.blockNumber(),canonical.blockHash(),canonical.fromAddress(),canonical.toAddress(),canonical.amount()));
        }
        String hash=sha(encode(summary));summary.put("canonical",proof.canonical());summary.put("checkedHead",proof.checkedHead());
        return new Evidence(row,summary,hash,proof,Instant.now());
    }
    private void changeSource(Map<String,Object> row,String state,BscDepositProof.TransferProof proof) {
        if(proof!=null) {
            var original=new CregisExceptionSourceService.Source(id(row,"projectId"),id(row,"cid"),text(row,"txid"),(int)id(row,"logIndex"),text(row,"address"),money(row,"grossAmount"));
            var canonical=new CregisExceptionSourceService.Source(id(row,"projectId"),id(row,"cid"),text(row,"txid"),proof.logIndex(),proof.toAddress(),proof.amount());
            sources.relocateCredited(original,canonical,operationNo(id(row,"id"),id(row,"version")+1));
        }
        if(db.eventStatus(id(row,"eventId"),text(row,"eventStatus"),state,proof==null?id(row,"blockNumber"):proof.blockNumber(),
                proof==null?text(row,"blockHash"):proof.blockHash(),proof==null?15:proof.confirmations(),proof==null?(int)id(row,"logIndex"):proof.logIndex())!=1)throw conflict("EVENT_CHANGED");
        if(db.depositStatus("CR-"+id(row,"cid"),id(row,"userId"),id(row,"ledgerId"),state)!=1)throw conflict("DEPOSIT_ORDER_MISSING");
    }
    private void append(Map<String,Object> row,String phase,long version,HoldBefore before,List<Event> events,Map<String,Object> evidence,String effect) {
        long incident=id(row,"id"),user=id(row,"userId");String no=operationNo(incident,version);
        var after=required(db.wallet(user));var raw=journals.journal(text(before.wallet(),"incarnation"),id(before.wallet(),"revision"),id(after,"revision"));
        var traces=new ArrayList<Map<String,Object>>();
        for(int i=0;i<raw.size();i++)traces.add(Map.of("sourceId","nx_cregis_reorg_operation:"+no,"eventIds",i==0?events.stream().map(Event::id).toList():List.of(),"journal",raw.get(i)));
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemaVersion","CREGIS_REORG_V1");snapshot.put("row",row);snapshot.put("phase",phase);snapshot.put("operationNo",no);
        var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion","CREGIS_REORG_V1");proof.put("cashBefore",cashSnapshot(before.wallet()));proof.put("cashAfter",cashSnapshot(after));proof.put("cashTrace",traces);proof.put("evidence",evidence);
        var operation=new LinkedHashMap<String,Object>();operation.put("no",no);operation.put("incident",incident);operation.put("version",version);operation.put("phase",phase);
        operation.put("fact","DEPOSIT:"+id(row,"ledgerId"));operation.put("effect",effect);operation.put("snapshot",encode(snapshot));operation.put("proof",encode(proof));operation.put("events",encode(events));
        if(db.operation(operation)!=1)throw conflict("OPERATION_CONFLICT");
        audit("OPERATION_"+phase,incident,0,Map.of("operationNo",no,"version",version,"originalFactId",operation.get("fact")));
        if(!raw.isEmpty())funds.bind(before.binding(),new SupportFundJournalFacade.ReceiptInput(no,"SUPPORT_CREGIS_REORG",phase,
                "nx_cregis_reorg_operation",no,"PRODUCTION",encode(events),encode(snapshot),encode(proof)));
        else if(!events.isEmpty())throw conflict("MISSING_CASH_JOURNAL");
    }
    private void lockSubject(Map<String,Object> row) {
        if(db.lockGate()==null)throw conflict("GATE_MISSING");
        if(db.lockUser(id(row,"userId"))==null)throw conflict("USER_MISSING");
        if(!A2ReplayContext.isReplaying() && (locks.countActiveByTarget("C","user",String.valueOf(id(row,"userId")))>0
                || locks.countActiveByTarget("D","deposit","CR-"+id(row,"cid"))>0))throw conflict("OBJECT_LOCKED_BY_A2");
    }
    private void checkProposal(Map<String,Object> proposal,long actor,long version) {
        if(!"MAKER_DONE".equals(proposal.get("status")) || id(proposal,"version")!=version)throw conflict("PROPOSAL_CHANGED");
        if(id(proposal,"makerId")==actor)throw new BizException(403,"CREGIS_REORG_DIFFERENT_CHECKER_REQUIRED");
        if(!admins.isActiveAdministrator(id(proposal,"makerId")))throw new BizException(403,"CREGIS_REORG_MAKER_INACTIVE");
    }
    private long requireAdmin(boolean write) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?,?> details) || !"ADMIN".equals(details.get("subjectType"))
                || !auth.getAuthorities().stream().anyMatch(a->"finance_d1_read".equals(a.getAuthority()))
                || write && !auth.getAuthorities().stream().anyMatch(a->"finance_d1_channel_manage".equals(a.getAuthority())))throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");
        long id;try{id=Long.parseLong(String.valueOf(auth.getPrincipal()));}catch(NumberFormatException invalid){throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");}
        if(id<=0 || !admins.isActiveAdministrator(id))throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");return id;
    }
    public static List<String> allowed(Map<String,Object> row) {
        var actions=new ArrayList<String>();String state=text(row,"status");
        if("INVESTIGATING".equals(state))actions.addAll(List.of("REVERSE","RELEASE_HOLD"));
        if(Set.of("REVERSED","REVERSED_DEBT").contains(state))actions.add("RESTORE");
        if("REVERSED_DEBT".equals(state) && money(row,"outstandingDebt").signum()>0)actions.add("COLLECT_DEBT");
        if(!"INVESTIGATING".equals(state) && money(row,"outstandingDebt").signum()==0 && money(row,"heldAmount").signum()==0
                && "FROZEN".equals(row.get("userStatus")) && "CREGIS_DEPOSIT_INCIDENT".equals(row.get("freezeSource")))actions.add("UNFREEZE");
        return actions;
    }
    public static String operationNo(long incident,long version){return "CR-REORG-"+incident+":"+version;}
    public static String depositOrigin(Map<String,Object> row) {
        String fact="DEPOSIT:"+id(row,"ledgerId");return "DEPOSIT:PAYMENT:DEPOSIT_ORDER:"+sha("DEPOSIT_ORDER|"+id(row,"projectId")+"|"+fact);
    }
    private static Account available(long user){return new Account(user,"USDT","available");}
    private static Account risk(long user,long incident){return new Account(user,"USDT","risk:CR-"+incident);}
    private static Event debit(String id,Account from,String origin,BigDecimal amount){return new Event(id,ffdd.opsconsole.finance.domain.SupportFundAllocation.Action.REORG_DEBIT,from,null,origin,amount,null);}
    private List<Event> decodeEvents(String text){try{return Arrays.asList(json.readValue(text,Event[].class));}catch(Exception bad){throw conflict("STORED_EVENTS_INVALID");}}
    private static void validateFinancialState(Map<String,Object> row) {
        BigDecimal net=money(row,"netAmount"),held=money(row,"heldAmount"),recovered=money(row,"recoveredAmount"),debt=money(row,"outstandingDebt");
        if(net.signum()<=0 || held.signum()<0 || recovered.signum()<0 || debt.signum()<0
                || held.compareTo(net)>0 || recovered.compareTo(net)>0 || debt.compareTo(net)>0)throw conflict("FINANCIAL_STATE_INVALID");
        String state=text(row,"status");
        if("INVESTIGATING".equals(state) && (recovered.signum()!=0 || debt.signum()!=0)
                || Set.of("REVERSED","REVERSED_DEBT").contains(state) && (held.signum()!=0 || recovered.add(debt).compareTo(net)!=0)
                || Set.of("RESTORED","RELEASED").contains(state) && (held.signum()!=0 || debt.signum()!=0))throw conflict("FINANCIAL_STATE_INVALID");
    }
    private static Map<String,Object> transport(Map<String,Object> row) {
        var value=new LinkedHashMap<>(row);
        value.replaceAll((key,entry)->entry instanceof BigDecimal amount?amount.toPlainString():entry);
        for(String key:List.of("id","incidentId","eventId","projectId","cid","userId","makerId","checkerId","ledgerId"))
            if(value.get(key)!=null)value.put(key,String.valueOf(value.get(key)));
        return value;
    }
    private static Map<String,Object> cashSnapshot(Map<String,Object> wallet) {
        var snapshot=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","sandbox","isDeleted","createdAt","incarnation","revision"))snapshot.put(key,wallet.get(key));
        return snapshot;
    }
    private static String fingerprint(Map<String,Object> row){return List.of("id","eventId","projectId","cid","userId","netAmount","heldAmount","recoveredAmount","outstandingDebt","status","version","txid","logIndex","address","grossAmount","blockNumber","blockHash","ledgerId","eventStatus","originalLogIndex","transferFrom","userStatus","freezeSource","freezeRef").stream().map(key->key+"="+row.get(key)).collect(java.util.stream.Collectors.joining("|"));}
    private static String walletFingerprint(Map<String,Object> wallet){return wallet.get("incarnation")+"|"+wallet.get("revision")+"|"+wallet.get("version")+"|"+wallet.get("usdtAvailable")+"|"+wallet.get("cregisRiskHeld")+"|"+wallet.get("cumulativeDeposit");}
    private static void reason(String reason){if(reason==null || reason.trim().length()<10 || reason.length()>255)throw new BizException(422,"CREGIS_REORG_REASON_REQUIRED");}
    private static void validatePage(long before,int limit){if(before<0 || limit<1 || limit>100)throw new BizException(422,"PAGE_INVALID");}
    private static Map<String,Object> page(List<? extends Map<String,Object>> rows,boolean more){var result=new LinkedHashMap<String,Object>();result.put("records",rows);result.put("hasMore",more);result.put("nextBeforeId",more&&!rows.isEmpty()?String.valueOf(rows.get(rows.size()-1).get("id")):null);return result;}
    private static void fresh(Evidence proof){if(proof.checkedAt().isBefore(Instant.now().minusSeconds(5)))throw conflict("PROOF_EXPIRED");}
    private void audit(String action,long incident,long actor,Map<String,Object> detail){audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_REORG_"+action).resourceType("CREGIS_INCIDENT").resourceId(String.valueOf(incident)).actorType(actor==0?"SYSTEM":"ADMIN").actorId(actor==0?null:actor).actorUsername(actor==0?"cregis-reconciler":"admin:"+actor).riskLevel("CRITICAL").result("SUCCESS").detail(detail).build());}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception bad){throw new IllegalStateException("CREGIS_REORG_SERIALIZATION_FAILED",bad);}}
    private static Map<String,Object> required(Map<String,Object> row){if(row==null || row.isEmpty())throw new BizException(404,"CREGIS_REORG_NOT_FOUND");return row;}
    private static long id(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static String text(Map<String,Object> row,String field){return String.valueOf(row.get(field));}
    private static BigDecimal money(Map<String,Object> row,String field){return new BigDecimal(String.valueOf(row.get(field)));}
    private static BizException conflict(String reason){return new BizException(409,"CREGIS_REORG_"+reason);}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
