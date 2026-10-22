package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Funds transport only. Does not credit/debit users, create withdrawal orders, or interpret preparation plans. */
@Service @RequiredArgsConstructor
public class CregisAutoForwardService {
    private final CregisProperties config;
    private final CregisAutoForwardMapper db;
    private final CregisFundsPreparationMapper addresses;
    private final CregisDepositMapper deposits;
    private final CregisPayoutControl payout;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisExceptionAdminGuard admins;
    private final AdminPermissionCache permissions;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final CregisSigner signer;
    private final PlatformTransactionManager transactions;
    private final AtomicLong cursor=new AtomicLong();

    public Map<String,Object> status() {
        admins.require(false);fixedAddresses();
        var result=new LinkedHashMap<>(required(db.policy(project())));
        for(String field:List.of("startEventId","makerId","pendingCollection","pendingPayment","reason","proposedAt"))result.putIfAbsent(field,null);
        result.put("runtimeApproved",config.isAutoForwardEnabled());
        result.put("collectionEnabled",on(result.get("collectionEnabled")));
        result.put("paymentEnabled",on(result.get("paymentEnabled")));
        result.put("executionEnabled",config.isAutoForwardEnabled()&&(on(result.get("collectionEnabled"))||on(result.get("paymentEnabled")))) ;
        result.put("amountPolicy","GROSS_COLLECTION_NET_PAYMENT_PLATFORM_GAS");
        result.put("platformAddress",CregisFundsPreparationService.PLATFORM);
        result.put("counterpartyAddress",CregisFundsPreparationService.COUNTERPARTY);
        result.put("projectId",Long.toString(project()));
        return result;
    }
    public Map<String,Object> tasks(long before,int limit) {
        admins.require(false);if(before<0||limit<1||limit>50)throw invalid();
        var rows=db.page(project(),before,limit+1);boolean more=rows.size()>limit;
        var records=rows.subList(0,Math.min(limit,rows.size()));
        var result=new LinkedHashMap<String,Object>();result.put("records",records);result.put("hasMore",more);
        result.put("nextBeforeId",more?text(records.get(records.size()-1),"id"):null);return result;
    }
    public Map<String,Object> policy(String key,String action,long version,Boolean collect,Boolean pay,String reason) {
        long actor=writer();reason(reason);
        if(version<0||!Set.of("PROPOSE","APPROVE","CANCEL","PAUSE").contains(Objects.toString(action,"")))throw invalid();
        var request=new TreeMap<String,Object>();request.put("action",action);request.put("version",version);
        request.put("collect",collect);request.put("pay",pay);request.put("reason",reason.trim());
        return command(actor,key,request,()->tx(()->{
            gate();var p=required(db.lockPolicy(project()));
            if(!"PAUSE".equals(action)&&number(p,"version")!=version)throw conflict();
            int changed;
            switch(action) {
                case "PAUSE" -> changed=db.pause(project(),reason.trim());
                case "PROPOSE" -> {
                    if(collect==null||pay==null||p.get("makerId")!=null)throw invalid();
                    if(collect||pay)activationReady();
                    changed=db.propose(project(),version,collect?1:0,pay?1:0,actor,reason.trim());
                }
                case "APPROVE" -> {
                    long maker=number(p,"makerId");if(maker<=0||maker==actor)throw new BizException(409,"CREGIS_AUTO_DIFFERENT_CHECKER_REQUIRED");
                    admins.requireActive(maker);
                    requireCurrentPermissions(maker);
                    if(on(p.get("pendingCollection"))||on(p.get("pendingPayment")))activationReady();
                    changed=db.apply(project(),version,on(p.get("pendingCollection"))?1:0,on(p.get("pendingPayment"))?1:0,reason.trim());
                }
                default -> {if(p.get("makerId")==null)throw conflict();changed=db.cancel(project(),version);}
            }
            if(changed!=1)throw conflict();record("POLICY_"+action,Long.toString(project()),actor,request);
            return status();
        }));
    }

    @Scheduled(fixedDelayString="${nexion.finance.cregis.auto-forward-poll-ms:60000}")
    public synchronized void tick() {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER)return;
        var p=required(db.policy(project()));
        if(config.isAutoForwardEnabled()&&on(p.get("collectionEnabled"))&&p.get("startEventId")!=null&&payout.enabled()) {
            for(long event:db.candidates(project(),number(p,"startEventId"))) {
                try {tx(()->{gate();var current=required(db.lockPolicy(project()));
                    if(!allowed(current,true)||number(current,"startEventId")>=event)return null;
                    fixedAddresses();var e=event(event);money(e);
                    db.enqueue(project(),e,CregisFundsPreparationService.PLATFORM,CregisFundsPreparationService.COUNTERPARTY,"CA-P-"+project()+"-"+event);
                    record("ENQUEUED",Long.toString(event),0,Map.of("eventId",event));return null;
                });}catch(DuplicateKeyException concurrent){/* Event and active-source unique constraints arbitrate concurrent schedulers. */}
                catch(RuntimeException blocked){/* No task or send is permitted when evidence/risk is unavailable. */}
            }
        }
        var rows=db.work(project(),cursor.get());if(rows.isEmpty()&&cursor.get()!=0){cursor.set(0);rows=db.work(project(),0);}
        for(var row:rows){long id=number(row,"id");cursor.set(id);
            try {process(row);}catch(RuntimeException unavailable){error(id,"CREGIS_AUTO_READ_OR_RISK_UNAVAILABLE");}
        }
    }
    private void process(Map<String,Object> row) {
        String state=text(row,"state");boolean collect=state.startsWith("COLLECTION")||state.equals("WAIT_COLLECTION");
        if(state.endsWith("SUBMITTING")) {
            Object raw=row.get("intentAt");LocalDateTime at=raw instanceof LocalDateTime t?t:raw instanceof java.sql.Timestamp t?t.toLocalDateTime():null;
            if(at!=null&&at.isBefore(LocalDateTime.now().minusMinutes(2)))tx(()->{gate();db.lockPolicy(project());var current=locked(number(row,"id"));
                if(text(current,"state").equals(state)&&number(current,"version")==number(row,"version"))save(current,collect?"COLLECTION_UNKNOWN":"PAYMENT_UNKNOWN","CREGIS_AUTO_SUBMISSION_UNKNOWN");return null;});
            return;
        }
        if(state.equals("WAIT_COLLECTION")||state.equals("WAIT_PAYMENT")) {
            var p=required(db.policy(project()));if(!allowed(p,collect))return;
            fixedAddresses();snapshot(row);verifyDeposit(row);
            if(!collect)proveCollection(row);
            String source=collect?text(row,"sourceAddress"):text(row,"platformAddress");BigDecimal amount=decimal(row,collect?"grossAmount":"netAmount");
            if(collect&&!router.provider().addressBelongs(CregisConstants.BSC_CHAIN_ID,source))throw invalid();
            var balance=chain.preparationBalance(source);
            if(new BigDecimal(balance.usdt()).compareTo(amount)<0) {error(number(row,"id"),"CREGIS_AUTO_USDT_INSUFFICIENT");return;}
            if(new BigDecimal(balance.bnb()).signum()<=0){error(number(row,"id"),"CREGIS_AUTO_BNB_REQUIRED");return;}
            Map<String,Object> intent=tx(()->{
                gate();var currentPolicy=required(db.lockPolicy(project()));var current=locked(number(row,"id"));
                if(!allowed(currentPolicy,collect)||!text(current,"state").equals(state)||number(current,"version")!=number(row,"version"))return null;
                snapshot(current);event(number(current,"eventId"));fixedAddresses();
                current.put(collect?"collectionFloor":"paymentFloor",balance.blockNumber());current.put("intentAt",LocalDateTime.now());
                save(current,collect?"COLLECTION_SUBMITTING":"PAYMENT_SUBMITTING",null);return locked(number(row,"id"));
            });
            if(intent!=null)dispatch(intent,collect);
            return;
        }
        long cid=number(row,collect?"collectionCid":"paymentCid");if(cid>0)observe(row,collect,cid);
    }
    /** The committed intent survives process death. Only this invocation may submit it once. */
    private void dispatch(Map<String,Object> intent,boolean collect) {
        tx(()->{
            gate();var p=required(db.lockPolicy(project()));var current=locked(number(intent,"id"));
            if(number(current,"version")!=number(intent,"version")||!text(current,"state").endsWith("SUBMITTING"))return null;
            if(!allowed(p,collect)){current.put("intentAt",null);save(current,collect?"WAIT_COLLECTION":"WAIT_PAYMENT","CREGIS_AUTO_PAUSED");return null;}
            snapshot(current);event(number(current,"eventId"));fixedAddresses();
            // The project gate serializes switch changes and outgoing requests. Recheck fresh chain evidence after acquiring it.
            try {
                verifyDeposit(current);if(!collect)proveCollection(current);
                String source=collect?text(current,"sourceAddress"):text(current,"platformAddress");
                if(collect&&!router.provider().addressBelongs(CregisConstants.BSC_CHAIN_ID,source))throw invalid();
                var balance=chain.preparationBalance(source);
                if(balance.blockNumber()<number(current,collect?"collectionFloor":"paymentFloor")
                        ||new BigDecimal(balance.usdt()).compareTo(decimal(current,collect?"grossAmount":"netAmount"))<0
                        ||new BigDecimal(balance.bnb()).signum()<=0)throw invalid();
            }catch(RuntimeException preSendBlocked){
                current.put("intentAt",null);save(current,collect?"WAIT_COLLECTION":"WAIT_PAYMENT","CREGIS_AUTO_READ_OR_RISK_UNAVAILABLE");return null;
            }
            try {
                long cid;
                if(collect)cid=router.provider().createCollection(collection(current));
                else {
                    var submitted=router.provider().createAutoForwardPayout(new CregisGateway.PayoutRequest(CregisConstants.USDT_BEP20_CURRENCY,
                        text(current,"counterpartyAddress"),decimal(current,"netAmount"),text(current,"paymentKey"),router.payoutCallbackUrl(),"UVEL automatic net forward"));
                    if(submitted==null||!text(current,"paymentKey").equals(submitted.thirdPartyId()))throw new IllegalStateException("CREGIS_AUTO_IDENTITY_CONFLICT");
                    cid=submitted.cid();
                }
                if(cid<=0)throw new IllegalStateException("CREGIS_AUTO_IDENTITY_CONFLICT");
                current.put(collect?"collectionCid":"paymentCid",cid);
                save(current,collect?"COLLECTION_PENDING":"PAYMENT_PENDING",null);
            }catch(CregisGatewayException failure){
                boolean rejected=failure.kind()==CregisGatewayException.Kind.REJECTED||failure.kind()==CregisGatewayException.Kind.CONFIGURATION;
                save(current,(collect?"COLLECTION_":"PAYMENT_")+(rejected?"FAILED":"UNKNOWN"),rejected?"CREGIS_AUTO_CREATE_REJECTED":"CREGIS_AUTO_SUBMISSION_UNKNOWN");
            }catch(RuntimeException uncertain){save(current,collect?"COLLECTION_UNKNOWN":"PAYMENT_UNKNOWN","CREGIS_AUTO_SUBMISSION_UNKNOWN");}
            return null;
        });
    }
    private void observe(Map<String,Object> candidate,boolean collect,long cid) {
        snapshot(candidate);String next=null,txid=null;
        if(collect) {
            var order=router.provider().queryCollection(cid,collection(candidate)).orElse(null);if(order==null)return;
            txid=order.txid();
            if(order.status()==1){verifyDeposit(candidate);prove(txid,text(candidate,"sourceAddress"),text(candidate,"platformAddress"),decimal(candidate,"grossAmount"),number(candidate,"collectionFloor"));next="WAIT_PAYMENT";}
            else if(order.status()==2)next="COLLECTION_UNKNOWN";
        }else{
            var order=router.provider().queryPayout(query(candidate,cid));txid=order.txid();
            if(order.status()==CregisGateway.PayoutStatus.SUCCEEDED){verifyDeposit(candidate);proveCollection(candidate);prove(txid,text(candidate,"platformAddress"),text(candidate,"counterpartyAddress"),decimal(candidate,"netAmount"),number(candidate,"paymentFloor"));next="COMPLETED";}
            else if(Set.of(2,3,4,7).contains(order.status().providerCode()))next=txid==null&&order.status().providerCode()!=7?"PAYMENT_FAILED":"PAYMENT_UNKNOWN";
        }
        String observedNext=next,observedTxid=txid;
        tx(()->{gate();db.lockPolicy(project());var row=locked(number(candidate,"id"));
            if(number(row,"version")!=number(candidate,"version")||number(row,collect?"collectionCid":"paymentCid")!=cid)return null;
            if(observedNext!=null) {
                if(observedNext.equals("WAIT_PAYMENT")||observedNext.equals("COMPLETED"))event(number(row,"eventId"));
                if(observedNext.equals(row.get("state"))&&Objects.equals(observedTxid,row.get(collect?"collectionTxid":"paymentTxid")))return null;
                row.put(collect?"collectionTxid":"paymentTxid",observedTxid);
                save(row,observedNext,observedNext.endsWith("FAILED")?"CREGIS_AUTO_PROVIDER_FAILED":observedNext.endsWith("UNKNOWN")?"CREGIS_AUTO_TRANSACTION_UNCERTAIN":null);
            }return null;
        });
    }
    public Map<String,Object> recover(String key,long id,long version,String stage,String cidText,boolean retry,String reason) {
        long actor=writer();reason(reason);if(id<=0||version<0||!Set.of("COLLECTION","PAYMENT").contains(Objects.toString(stage,"")))throw invalid();
        long cid=0;if(!retry){try{if(cidText==null||!cidText.matches("[1-9][0-9]{0,18}"))throw invalid();cid=Long.parseLong(cidText);}catch(NumberFormatException bad){throw invalid();}}
        long originalCid=cid;var request=Map.<String,Object>of("id",id,"version",version,"stage",stage,"cid",cid,"retry",retry,"reason",reason.trim());
        return command(actor,key,request,()->tx(()->{
            gate();db.lockPolicy(project());var row=locked(id);if(number(row,"version")!=version)throw conflict();snapshot(row);
            String state=text(row,"state"),cidField=stage.equals("COLLECTION")?"collectionCid":"paymentCid";
            if(retry){
                if(!state.equals(stage+"_FAILED")||number(row,cidField)!=0||!"CREGIS_AUTO_CREATE_REJECTED".equals(row.get("lastError")))throw conflict();
                row.put("intentAt",null);save(row,stage.equals("COLLECTION")?"WAIT_COLLECTION":"WAIT_PAYMENT",null);
            }else{
                if(!Set.of(stage+"_UNKNOWN",stage+"_FAILED").contains(state)||row.get("intentAt")==null
                        ||number(row,cidField)>0&&number(row,cidField)!=originalCid)throw conflict();
                if(stage.equals("COLLECTION")){if(router.provider().queryCollection(originalCid,collection(row)).isEmpty())throw conflict();}
                else router.provider().queryPayout(query(row,originalCid));
                row.put(cidField,originalCid);save(row,stage+"_PENDING",null);
            }
            record(retry?"RETRY_REQUESTED":"ORIGINAL_CID_BOUND",Long.toString(id),actor,request);return locked(id);
        }));
    }
    /** An authenticated callback is only a query hint, never a success or a send authorization. */
    public boolean callback(Map<String,Object> body,String peer) {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER||!config.allowsCallbackSourceIp(peer)||body==null)return false;
        var row=db.byKey(project(),Objects.toString(body.get("third_party_id"),""));if(row==null||row.get("intentAt")==null)return false;
        Object raw=body.get("cid");if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<=0)return false;
        long cid=((Number)raw).longValue();long expected=number(row,"paymentCid");
        var verified=new CregisCallbackVerifier(signer,project(),config.getApiKey(),Clock.systemUTC(),Duration.ofMinutes(5))
            .verifyPayout(body,new CregisCallbackVerifier.ExpectedPayout(expected>0?expected:cid,text(row,"paymentKey"),text(row,"counterpartyAddress"),decimal(row,"netAmount")));
        if(!verified.valid())return false;
        return tx(()->{gate();db.lockPolicy(project());var current=locked(number(row,"id"));
            if(!Set.of("PAYMENT_SUBMITTING","PAYMENT_PENDING","PAYMENT_UNKNOWN","PAYMENT_FAILED","COMPLETED").contains(text(current,"state")))return false;
            if(number(current,"paymentCid")>0)return number(current,"paymentCid")==cid;
            current.put("paymentCid",cid);save(current,"PAYMENT_PENDING","CREGIS_AUTO_CALLBACK_QUERY_REQUIRED");return true;
        });
    }
    private void verifyDeposit(Map<String,Object> row) {
        Map<String,Object> e=tx(()->event(number(row,"eventId")));money(e);
        if(!text(row,"sourceAddress").equalsIgnoreCase(text(e,"address"))||decimal(row,"grossAmount").compareTo(decimal(e,"grossAmount"))!=0
                ||decimal(row,"netAmount").compareTo(decimal(e,"netAmount"))!=0||decimal(row,"feeAmount").compareTo(decimal(e,"feeAmount"))!=0)throw invalid();
        var proof=chain.verifyExact(text(e,"txid"),Math.toIntExact(number(e,"logIndex")),text(e,"address"),decimal(e,"grossAmount"),number(e,"blockNumber"));
        if(proof==null)throw invalid();
    }
    private void proveCollection(Map<String,Object> row){prove(text(row,"collectionTxid"),text(row,"sourceAddress"),text(row,"platformAddress"),decimal(row,"grossAmount"),number(row,"collectionFloor"));}
    private void prove(String txid,String from,String to,BigDecimal amount,long floor) {
        if(floor<=0||txid.isBlank())throw invalid();
        var proof=chain.verifyPayout(txid,to,amount).orElseThrow(CregisAutoForwardService::invalid);
        if(proof.confirmations()<payout.requiredConfirmations()||proof.blockNumber()<floor||!from.equalsIgnoreCase(proof.fromAddress()))throw invalid();
    }
    private Map<String,Object> event(long id){var e=required(db.lockEvent(project(),id));if(!"CREDITED".equals(e.get("status")))throw invalid();return e;}
    private void fixedAddresses(){var a=required(addresses.config(project()));if(!CregisFundsPreparationService.PLATFORM.equals(a.get("platformAddress"))
            ||!CregisFundsPreparationService.COUNTERPARTY.equals(a.get("counterpartyAddress"))||!"BSC".equals(a.get("network"))||!"USDT".equals(a.get("asset")))throw invalid();}
    private void snapshot(Map<String,Object> row){if(!CregisFundsPreparationService.PLATFORM.equals(row.get("platformAddress"))||!CregisFundsPreparationService.COUNTERPARTY.equals(row.get("counterpartyAddress")))throw invalid();money(row);}
    private void money(Map<String,Object> row){for(String field:List.of("grossAmount","netAmount"))if(CregisAmount.normalizePositive(decimal(row,field))==null)throw invalid();
        if(decimal(row,"feeAmount").signum()<0||decimal(row,"grossAmount").compareTo(decimal(row,"netAmount").add(decimal(row,"feeAmount")))!=0)throw invalid();}
    private boolean allowed(Map<String,Object> policy,boolean collect){return config.isAutoForwardEnabled()&&on(policy.get(collect?"collectionEnabled":"paymentEnabled"))&&payout.enabled();}
    private void activationReady(){fixedAddresses();if(!config.isAutoForwardEnabled()||config.getMode()!=CregisProperties.Mode.PROVIDER||!payout.enabled())throw new BizException(409,"CREGIS_AUTO_ACTIVATION_NOT_APPROVED");}
    private void gate(){if(deposits.lockProvisionGate()==null)throw invalid();}
    private Map<String,Object> locked(long id){return required(db.lock(project(),id));}
    private void save(Map<String,Object> row,String state,String error){if(db.save(project(),row,state,error)!=1)throw conflict();
        if(state.endsWith("UNKNOWN"))db.holdUnknown(project(),number(row,"id"),Objects.toString(error,"CREGIS_AUTO_SUBMISSION_UNKNOWN"));
        if(state.equals("WAIT_PAYMENT")||state.equals("COMPLETED")||state.equals("PAYMENT_FAILED")&&number(row,"paymentCid")>0&&row.get("paymentTxid")==null)db.resolveUnknown(project(),number(row,"id"));
        record(state,text(row,"id"),0,Map.of("eventId",text(row,"eventId"),"error",Objects.toString(error,"")));}
    private void error(long id,String error){tx(()->{gate();db.lockPolicy(project());var row=locked(id);if(!error.equals(row.get("lastError")))save(row,text(row,"state"),error);return null;});}
    private CregisGateway.CollectionRequest collection(Map<String,Object> row){return new CregisGateway.CollectionRequest(text(row,"sourceAddress"),text(row,"platformAddress"),decimal(row,"grossAmount"));}
    private CregisGateway.PayoutQuery query(Map<String,Object> row,long cid){return new CregisGateway.PayoutQuery(cid,text(row,"paymentKey"),text(row,"counterpartyAddress"),decimal(row,"netAmount"));}
    private long writer(){long actor=admins.require(true);if(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().noneMatch(a->"finance_d2_withdrawal_approve".equals(a.getAuthority())))throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");requireCurrentPermissions(actor);return actor;}
    private void requireCurrentPermissions(long actor){if(!permissions.getPermissionCodes(actor).containsAll(Set.of("finance_d1_read","finance_d1_channel_manage","finance_d2_withdrawal_approve")))throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");}
    private <T>T tx(Supplier<T> action){return new TransactionTemplate(transactions).execute(s->action.get());}
    @SuppressWarnings("unchecked") private Map<String,Object> command(long actor,String key,Object request,Supplier<Map<String,Object>> action){
        try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
            return idempotency.executeRetained("CREGIS_AUTO_FORWARD:"+actor,key,hash,Map.class,action::get);
        }catch(java.security.NoSuchAlgorithmException|com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("CREGIS_AUTO_HASH_FAILED");}}
    private void record(String action,String id,long actor,Object detail){audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_AUTO_FORWARD_"+action).resourceType("CREGIS_AUTO_FORWARD").resourceId(id).bizNo(id).actorId(actor).actorType(actor==0?"SYSTEM":"ADMIN").actorUsername(actor==0?"cregis-auto-forward":"admin:"+actor).riskLevel("HIGH").result("SUCCESS").detail(detail).build());}
    private long project(){if(config.getProjectId()<=0)throw invalid();return config.getProjectId();}
    private static void reason(String r){if(r==null||r.trim().length()<10||r.trim().length()>300)throw invalid();}
    private static Map<String,Object> required(Map<String,Object> row){if(row==null)throw invalid();return row;}
    static String text(Map<String,Object> row,String name){return Objects.toString(row.get(name),"");}
    static long number(Map<String,Object> row,String name){return row.get(name)==null?0:Long.parseLong(row.get(name).toString());}
    static BigDecimal decimal(Map<String,Object> row,String name){return new BigDecimal(text(row,name));}
    private static boolean on(Object v){return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()==1;}
    private static BizException invalid(){return new BizException(409,"CREGIS_AUTO_EVIDENCE_OR_CONFIGURATION_INVALID");}
    private static BizException conflict(){return new BizException(409,"CREGIS_AUTO_STATE_CONFLICT");}
}
