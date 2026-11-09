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
    private final Map<CregisNetwork,Long> cursors=new EnumMap<>(CregisNetwork.class);

    public Map<String,Object> status(CregisNetwork network) {
        admins.require(false);
        var result=new LinkedHashMap<>(required(db.policy(project(),network)));
        for(String field:List.of("startEventId","makerId","pendingCollection","pendingPayment","reason","proposedAt"))result.putIfAbsent(field,null);
        result.put("network",network.network());result.put("gasSymbol",network.gasSymbol());
        result.put("configured",addressesConfigured(network));
        result.put("runtimeApproved",config.autoForwardNetworkEnabled(network));
        result.put("collectionEnabled",on(result.get("collectionEnabled")));
        result.put("paymentEnabled",on(result.get("paymentEnabled")));
        result.put("executionEnabled",allowed(result,true,network)||allowed(result,false,network));
        result.put("amountPolicy","GROSS_COLLECTION_NET_PAYMENT_PLATFORM_GAS");
        result.put("platformAddress",addressesConfigured(network)?network.normalizeAddress(config.autoForwardPlatform(network)):null);
        result.put("counterpartyAddress",addressesConfigured(network)?network.normalizeAddress(config.autoForwardCounterparty(network)):null);
        result.put("projectId",Long.toString(project()));
        return result;
    }
    public Map<String,Object> tasks(CregisNetwork network,long before,int limit) {
        admins.require(false);if(before<0||limit<1||limit>50)throw invalid();
        var rows=db.page(project(),network,before,limit+1);boolean more=rows.size()>limit;
        var records=rows.subList(0,Math.min(limit,rows.size()));
        var result=new LinkedHashMap<String,Object>();result.put("records",records);result.put("hasMore",more);
        result.put("nextBeforeId",more?text(records.get(records.size()-1),"id"):null);return result;
    }
    public Map<String,Object> policy(CregisNetwork network,String key,String action,long version,Boolean collect,Boolean pay,String reason) {
        long actor=writer();reason(reason);
        if(version<0||!Set.of("PROPOSE","APPROVE","CANCEL","PAUSE").contains(Objects.toString(action,"")))throw invalid();
        var request=new TreeMap<String,Object>();if(network!=CregisNetwork.BEP20)request.put("network",network.network());request.put("action",action);request.put("version",version);
        request.put("collect",collect);request.put("pay",pay);request.put("reason",reason.trim());
        return command(actor,key,request,()->tx(()->{
            gate();var p=required(db.lockPolicy(project(),network));
            if(!"PAUSE".equals(action)&&number(p,"version")!=version)throw conflict();
            int changed;
            switch(action) {
                case "PAUSE" -> changed=db.pause(project(),network,reason.trim());
                case "PROPOSE" -> {
                    if(collect==null||pay==null||p.get("makerId")!=null)throw invalid();
                    if(collect||pay)activationReady(network);
                    changed=db.propose(project(),network,version,collect?1:0,pay?1:0,actor,reason.trim());
                }
                case "APPROVE" -> {
                    long maker=number(p,"makerId");if(maker<=0||maker==actor)throw new BizException(409,"CREGIS_AUTO_DIFFERENT_CHECKER_REQUIRED");
                    admins.requireActive(maker);
                    requireCurrentPermissions(maker);
                    if(on(p.get("pendingCollection"))||on(p.get("pendingPayment")))activationReady(network);
                    changed=db.apply(project(),network,version,on(p.get("pendingCollection"))?1:0,on(p.get("pendingPayment"))?1:0,reason.trim());
                }
                default -> {if(p.get("makerId")==null)throw conflict();changed=db.cancel(project(),network,version);}
            }
            if(changed!=1)throw conflict();record("POLICY_"+action,Long.toString(project()),actor,request);
            return status(network);
        }));
    }

    @Scheduled(fixedDelayString="${nexion.finance.cregis.auto-forward-poll-ms:60000}")
    public synchronized void tick() {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER)return;
        for(var network:CregisNetwork.values()) tick(network);
    }
    private void tick(CregisNetwork network) {
        var p=required(db.policy(project(),network));
        if(config.autoForwardNetworkEnabled(network)&&on(p.get("collectionEnabled"))&&p.get("startEventId")!=null&&payout.enabled(network)) {
            for(long event:db.candidates(project(),network,number(p,"startEventId"))) {
                try {tx(()->{gate();var current=required(db.lockPolicy(project(),network));
                    if(!allowed(current,true,network)||number(current,"startEventId")>=event)return null;
                    fixedAddresses(network);var e=event(event,network);money(e);
                    db.enqueue(project(),e,network.normalizeAddress(config.autoForwardPlatform(network)),network.normalizeAddress(config.autoForwardCounterparty(network)),
                        "CA-P-"+(network==CregisNetwork.BEP20?"":network.network()+"-")+project()+"-"+event);
                    record("ENQUEUED",Long.toString(event),0,Map.of("eventId",event,"network",network.network()));return null;
                });}catch(DuplicateKeyException concurrent){/* Event and active-source unique constraints arbitrate concurrent schedulers. */}
                catch(RuntimeException blocked){/* No task or send is permitted when evidence/risk is unavailable. */}
            }
        }
        long cursor=cursors.getOrDefault(network,0L);
        var rows=db.work(project(),network,cursor);if(rows.isEmpty()&&cursor!=0){cursors.put(network,0L);rows=db.work(project(),network,0);}
        for(var row:rows){long id=number(row,"id");cursors.put(network,id);
            try {process(row);}catch(RuntimeException unavailable){error(id,network,"CREGIS_AUTO_READ_OR_RISK_UNAVAILABLE");}
        }
    }
    private void process(Map<String,Object> row) {
        var network=network(row);
        String state=text(row,"state");boolean collect=state.startsWith("COLLECTION")||state.equals("WAIT_COLLECTION");
        if(state.endsWith("SUBMITTING")) {
            Object raw=row.get("intentAt");LocalDateTime at=raw instanceof LocalDateTime t?t:raw instanceof java.sql.Timestamp t?t.toLocalDateTime():null;
            if(at!=null&&at.isBefore(LocalDateTime.now().minusMinutes(2)))tx(()->{gate();db.lockPolicy(project(),network);var current=locked(number(row,"id"),network);
                if(text(current,"state").equals(state)&&number(current,"version")==number(row,"version"))save(current,collect?"COLLECTION_UNKNOWN":"PAYMENT_UNKNOWN","CREGIS_AUTO_SUBMISSION_UNKNOWN");return null;});
            return;
        }
        if(state.equals("WAIT_COLLECTION")||state.equals("WAIT_PAYMENT")) {
            var p=required(db.policy(project(),network));if(!allowed(p,collect,network))return;
            fixedAddresses(network);snapshot(row);verifyDeposit(row);
            if(!collect)proveCollection(row);
            String source=collect?text(row,"sourceAddress"):text(row,"platformAddress");BigDecimal amount=decimal(row,collect?"grossAmount":"netAmount");
            if(collect&&!router.provider().addressBelongs(network.chainId(),source))throw invalid();
            var balance=chain.preparationBalance(network,source);
            if(new BigDecimal(balance.usdt()).compareTo(amount)<0) {error(number(row,"id"),network,"CREGIS_AUTO_USDT_INSUFFICIENT");return;}
            if(new BigDecimal(balance.nativeBalance()).signum()<=0){error(number(row,"id"),network,"CREGIS_AUTO_"+network.gasSymbol()+"_REQUIRED");return;}
            Map<String,Object> intent=tx(()->{
                gate();var currentPolicy=required(db.lockPolicy(project(),network));var current=locked(number(row,"id"),network);
                if(!allowed(currentPolicy,collect,network)||!text(current,"state").equals(state)||number(current,"version")!=number(row,"version"))return null;
                snapshot(current);event(number(current,"eventId"),network);fixedAddresses(network);
                current.put(collect?"collectionFloor":"paymentFloor",balance.blockNumber());current.put("intentAt",LocalDateTime.now());
                save(current,collect?"COLLECTION_SUBMITTING":"PAYMENT_SUBMITTING",null);return locked(number(row,"id"),network);
            });
            if(intent!=null)dispatch(intent,collect);
            return;
        }
        long cid=number(row,collect?"collectionCid":"paymentCid");if(cid>0)observe(row,collect,cid);
    }
    /** The committed intent survives process death. Only this invocation may submit it once. */
    private void dispatch(Map<String,Object> intent,boolean collect) {
        var network=network(intent);
        tx(()->{
            gate();var p=required(db.lockPolicy(project(),network));var current=locked(number(intent,"id"),network);
            if(number(current,"version")!=number(intent,"version")||!text(current,"state").endsWith("SUBMITTING"))return null;
            if(!allowed(p,collect,network)){current.put("intentAt",null);save(current,collect?"WAIT_COLLECTION":"WAIT_PAYMENT","CREGIS_AUTO_PAUSED");return null;}
            snapshot(current);event(number(current,"eventId"),network);fixedAddresses(network);
            // The project gate serializes switch changes and outgoing requests. Recheck fresh chain evidence after acquiring it.
            try {
                verifyDeposit(current);if(!collect)proveCollection(current);
                String source=collect?text(current,"sourceAddress"):text(current,"platformAddress");
                if(collect&&!router.provider().addressBelongs(network.chainId(),source))throw invalid();
                var balance=chain.preparationBalance(network,source);
                if(balance.blockNumber()<number(current,collect?"collectionFloor":"paymentFloor")
                        ||new BigDecimal(balance.usdt()).compareTo(decimal(current,collect?"grossAmount":"netAmount"))<0
                        ||new BigDecimal(balance.nativeBalance()).signum()<=0)throw invalid();
            }catch(RuntimeException preSendBlocked){
                current.put("intentAt",null);save(current,collect?"WAIT_COLLECTION":"WAIT_PAYMENT","CREGIS_AUTO_READ_OR_RISK_UNAVAILABLE");return null;
            }
            try {
                long cid;
                if(collect)cid=router.provider().createCollection(collection(current));
                else {
                    var submitted=router.provider().createAutoForwardPayout(new CregisGateway.PayoutRequest(network.currency(),
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
        var network=network(candidate);
        snapshot(candidate);String next=null,txid=null;
        if(collect) {
            var order=router.provider().queryCollection(cid,collection(candidate)).orElse(null);if(order==null)return;
            txid=order.txid();
            if(order.status()==1){verifyDeposit(candidate);prove(network,txid,text(candidate,"sourceAddress"),text(candidate,"platformAddress"),decimal(candidate,"grossAmount"),number(candidate,"collectionFloor"));next="WAIT_PAYMENT";}
            else if(order.status()==2)next="COLLECTION_UNKNOWN";
        }else{
            var order=router.provider().queryPayout(query(candidate,cid));txid=order.txid();
            if(order.status()==CregisGateway.PayoutStatus.SUCCEEDED){verifyDeposit(candidate);proveCollection(candidate);prove(network,txid,text(candidate,"platformAddress"),text(candidate,"counterpartyAddress"),decimal(candidate,"netAmount"),number(candidate,"paymentFloor"));next="COMPLETED";}
            else if(Set.of(2,3,4,7).contains(order.status().providerCode()))next=txid==null&&order.status().providerCode()!=7?"PAYMENT_FAILED":"PAYMENT_UNKNOWN";
        }
        String observedNext=next,observedTxid=txid;
        tx(()->{gate();db.lockPolicy(project(),network);var row=locked(number(candidate,"id"),network);
            if(number(row,"version")!=number(candidate,"version")||number(row,collect?"collectionCid":"paymentCid")!=cid)return null;
            if(observedNext!=null) {
                if(observedNext.equals("WAIT_PAYMENT")||observedNext.equals("COMPLETED"))event(number(row,"eventId"),network);
                if(observedNext.equals(row.get("state"))&&Objects.equals(observedTxid,row.get(collect?"collectionTxid":"paymentTxid")))return null;
                row.put(collect?"collectionTxid":"paymentTxid",observedTxid);
                save(row,observedNext,observedNext.endsWith("FAILED")?"CREGIS_AUTO_PROVIDER_FAILED":observedNext.endsWith("UNKNOWN")?"CREGIS_AUTO_TRANSACTION_UNCERTAIN":null);
            }return null;
        });
    }
    public Map<String,Object> recover(CregisNetwork network,String key,long id,long version,String stage,String cidText,boolean retry,String reason) {
        long actor=writer();reason(reason);if(id<=0||version<0||!Set.of("COLLECTION","PAYMENT").contains(Objects.toString(stage,"")))throw invalid();
        long cid=0;if(!retry){try{if(cidText==null||!cidText.matches("[1-9][0-9]{0,18}"))throw invalid();cid=Long.parseLong(cidText);}catch(NumberFormatException bad){throw invalid();}}
        long originalCid=cid;var request=network==CregisNetwork.BEP20
                ?Map.<String,Object>of("id",id,"version",version,"stage",stage,"cid",cid,"retry",retry,"reason",reason.trim())
                :Map.<String,Object>of("network",network.network(),"id",id,"version",version,"stage",stage,"cid",cid,"retry",retry,"reason",reason.trim());
        return command(actor,key,request,()->tx(()->{
            gate();db.lockPolicy(project(),network);var row=locked(id,network);if(number(row,"version")!=version)throw conflict();snapshot(row);
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
            record(retry?"RETRY_REQUESTED":"ORIGINAL_CID_BOUND",Long.toString(id),actor,request);return locked(id,network);
        }));
    }
    /** An authenticated callback is only a query hint, never a success or a send authorization. */
    public boolean callback(Map<String,Object> body,String peer) {
        if(config.getMode()!=CregisProperties.Mode.PROVIDER||!config.allowsCallbackSourceIp(peer)||body==null)return false;
        var row=db.byKey(project(),Objects.toString(body.get("third_party_id"),""));if(row==null||row.get("intentAt")==null)return false;
        var network=network(row);
        Object raw=body.get("cid");if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<=0)return false;
        long cid=((Number)raw).longValue();long expected=number(row,"paymentCid");
        var verified=new CregisCallbackVerifier(signer,project(),config.getApiKey(),Clock.systemUTC(),Duration.ofMinutes(5))
            .verifyPayout(body,new CregisCallbackVerifier.ExpectedPayout(expected>0?expected:cid,text(row,"paymentKey"),text(row,"counterpartyAddress"),decimal(row,"netAmount"),network));
        if(!verified.valid())return false;
        return tx(()->{gate();db.lockPolicy(project(),network);var current=locked(number(row,"id"),network);
            if(!Set.of("PAYMENT_SUBMITTING","PAYMENT_PENDING","PAYMENT_UNKNOWN","PAYMENT_FAILED","COMPLETED").contains(text(current,"state")))return false;
            if(number(current,"paymentCid")>0)return number(current,"paymentCid")==cid;
            current.put("paymentCid",cid);save(current,"PAYMENT_PENDING","CREGIS_AUTO_CALLBACK_QUERY_REQUIRED");return true;
        });
    }
    private void verifyDeposit(Map<String,Object> row) {
        var network=network(row);Map<String,Object> e=tx(()->event(number(row,"eventId"),network));money(e);
        if(!network.addressEquals(text(row,"sourceAddress"),text(e,"address"))||decimal(row,"grossAmount").compareTo(decimal(e,"grossAmount"))!=0
                ||decimal(row,"netAmount").compareTo(decimal(e,"netAmount"))!=0||decimal(row,"feeAmount").compareTo(decimal(e,"feeAmount"))!=0)throw invalid();
        var proof=chain.verifyExact(network,text(e,"txid"),Math.toIntExact(number(e,"logIndex")),text(e,"address"),decimal(e,"grossAmount"),number(e,"blockNumber"));
        if(proof==null)throw invalid();
    }
    private void proveCollection(Map<String,Object> row){prove(network(row),text(row,"collectionTxid"),text(row,"sourceAddress"),text(row,"platformAddress"),decimal(row,"grossAmount"),number(row,"collectionFloor"));}
    private void prove(CregisNetwork network,String txid,String from,String to,BigDecimal amount,long floor) {
        if(floor<=0||txid.isBlank())throw invalid();
        var proof=chain.verifyPayout(network,txid,to,amount).orElseThrow(CregisAutoForwardService::invalid);
        if(proof.confirmations()<payout.requiredConfirmations()||proof.blockNumber()<floor||!network.addressEquals(from,proof.fromAddress()))throw invalid();
    }
    private Map<String,Object> event(long id,CregisNetwork network){var e=required(db.lockEvent(project(),network,id));if(!"CREDITED".equals(e.get("status")))throw invalid();return e;}
    private boolean addressesConfigured(CregisNetwork network) {
        if(!config.autoForwardAddressesConfigured(network))return false;
        if(network!=CregisNetwork.BEP20)return true;
        var a=addresses.config(project());return a!=null&&CregisFundsPreparationService.PLATFORM.equals(a.get("platformAddress"))
            &&CregisFundsPreparationService.COUNTERPARTY.equals(a.get("counterpartyAddress"))&&"BSC".equals(a.get("network"))&&"USDT".equals(a.get("asset"));
    }
    private void fixedAddresses(CregisNetwork network){if(!addressesConfigured(network))throw invalid();}
    private void snapshot(Map<String,Object> row){var network=network(row);fixedAddresses(network);
        if(!network.addressEquals(config.autoForwardPlatform(network),text(row,"platformAddress"))
                ||!network.addressEquals(config.autoForwardCounterparty(network),text(row,"counterpartyAddress")))throw invalid();money(row);}
    private void money(Map<String,Object> row){for(String field:List.of("grossAmount","netAmount"))if(CregisAmount.normalizePositive(decimal(row,field))==null)throw invalid();
        if(decimal(row,"feeAmount").signum()<0||decimal(row,"grossAmount").compareTo(decimal(row,"netAmount").add(decimal(row,"feeAmount")))!=0)throw invalid();}
    private boolean allowed(Map<String,Object> policy,boolean collect,CregisNetwork network){return config.autoForwardNetworkEnabled(network)
        &&addressesConfigured(network)&&on(policy.get(collect?"collectionEnabled":"paymentEnabled"))&&payout.enabled(network);}
    private void activationReady(CregisNetwork network){fixedAddresses(network);if(!config.autoForwardNetworkEnabled(network)||config.getMode()!=CregisProperties.Mode.PROVIDER||!payout.enabled(network))throw new BizException(409,"CREGIS_AUTO_ACTIVATION_NOT_APPROVED");}
    private void gate(){if(deposits.lockProvisionGate()==null)throw invalid();}
    private Map<String,Object> locked(long id,CregisNetwork network){return required(db.lock(project(),network,id));}
    private void save(Map<String,Object> row,String state,String error){if(db.save(project(),row,state,error)!=1)throw conflict();
        if(state.endsWith("UNKNOWN"))db.holdUnknown(project(),number(row,"id"),Objects.toString(error,"CREGIS_AUTO_SUBMISSION_UNKNOWN"));
        if(state.equals("WAIT_PAYMENT")||state.equals("COMPLETED")||state.equals("PAYMENT_FAILED")&&number(row,"paymentCid")>0&&row.get("paymentTxid")==null)db.resolveUnknown(project(),number(row,"id"));
        record(state,text(row,"id"),0,Map.of("eventId",text(row,"eventId"),"error",Objects.toString(error,"")));}
    private void error(long id,CregisNetwork network,String error){tx(()->{gate();db.lockPolicy(project(),network);var row=locked(id,network);if(!error.equals(row.get("lastError")))save(row,text(row,"state"),error);return null;});}
    private CregisGateway.CollectionRequest collection(Map<String,Object> row){return new CregisGateway.CollectionRequest(text(row,"sourceAddress"),text(row,"platformAddress"),decimal(row,"grossAmount"),network(row));}
    private CregisGateway.PayoutQuery query(Map<String,Object> row,long cid){return new CregisGateway.PayoutQuery(cid,text(row,"paymentKey"),text(row,"counterpartyAddress"),decimal(row,"netAmount"),network(row).currency());}
    private CregisNetwork network(Map<String,Object> row){return CregisNetwork.fromChainId(text(row,"chainId"));}
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
