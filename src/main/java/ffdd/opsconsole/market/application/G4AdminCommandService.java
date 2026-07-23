package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Durable direct-execution boundary for G4 admin commands. */
@Service
@RequiredArgsConstructor
public class G4AdminCommandService {
    private final OpsNexMarketService marketService;
    private final AppGenesisMapper mapper;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final Clock clock;

    @Transactional
    public ApiResult<Map<String,Object>> updateParam(String key,String idem,NexMarketValueUpdateRequest request){
        requireCommand(idem,request);
        if(List.of("dividend","divBase","airdropPct","emissionCurve").contains(key)) requireDecision(request.decisionRef());
        return once("PARAM:"+key,idem,request,()->{
            ApiResult<Map<String,Object>> result=marketService.updateGenesisParam(idem,key,request);
            requireSuccess(result);
            audit.recordRequired(AuditLogWriteRequest.builder().action("G4_GENESIS_PARAM_CHANGED")
                    .resourceType("GENESIS_PARAM").resourceId(key).bizNo("G4P-"+key)
                    .actorType("ADMIN").actorUsername(AdminActorResolver.resolve(request.operator())).method("PATCH")
                    .path("/api/admin/market/nex/genesis/params/"+key)
                    .result("SUCCESS").riskLevel("HIGH").detail(linked(
                            "idempotencyKey",idem,"reason",request.reason().trim(),"paramKey",key,
                            "newValue",request.value(),"decisionRef",text(request.decisionRef(),"NOT_REQUIRED"))).build());
            outbox.publish("GENESIS_PARAM",key,"admin.genesis_param_changed",linked(
                    "paramKey",key,"newValue",request.value(),"decisionRef",text(request.decisionRef(),"NOT_REQUIRED")));
            return result;
        });
    }

    @Transactional
    public ApiResult<Map<String,Object>> pauseMarket(String idem,NexMarketValueUpdateRequest request){
        requireCommand(idem,request);
        if(!"false".equalsIgnoreCase(String.valueOf(request.value()))) throw new BizException(422,"G4_RESTORE_ONLY_FROM_J1");
        requireText(request.triggerBasis(),"G4_TRIGGER_BASIS_REQUIRED",8,200);
        requireText(request.dispositionPlan(),"DISPOSITION_PLAN_REQUIRED",8,500);
        return once("MARKET_PAUSE",idem,request,()->{
            ApiResult<Map<String,Object>> result=marketService.updateGenesisMarketStatus(idem,request);
            requireSuccess(result);
            outbox.publish("GENESIS_MARKET","genesis","admin.genesis_market_paused",linked(
                    "triggerBasis",request.triggerBasis().trim(),"dispositionPlan",request.dispositionPlan().trim()));
            return result;
        });
    }

    @Transactional
    public ApiResult<Map<String,Object>> rerunEmission(String idem,String batchNo,NexMarketValueUpdateRequest request){
        requireCommand(idem,request);
        requireDecision(request.decisionRef());
        String normalized=batchNo==null?"":batchNo.trim();
        if(!normalized.matches("[A-Za-z0-9-]{3,32}")) throw new BizException(422,"G4_GENESIS_BATCH_NO_INVALID");
        return once("EMISSION:"+normalized,idem,request,()->rerunInternal(idem,normalized,request));
    }

    private ApiResult<Map<String,Object>> rerunInternal(String idem,String batchNo,NexMarketValueUpdateRequest request){
        boolean open=config.activeValue("growth.phase.genesis_emissions_open")
                .map(v->List.of("1","true","on","enabled","open").contains(v.trim().toLowerCase())).orElse(false);
        if(!open) throw new BizException(409,"G4_GENESIS_EMISSION_GATE_CLOSED");
        AppGenesisMapper.SeriesRow series=mapper.lockActiveSeries();
        if(series==null) throw new BizException(409,"G4_GENESIS_SERIES_NOT_FOUND");
        List<AppGenesisMapper.HoldingRow> holdings=mapper.lockEmissionHoldings();
        BigDecimal rate=nz(series.dailyEmissionRatePct());
        // Genesis daily emission is a product liability based on the immutable series unit price.
        // A secondary-market resale price must never amplify or shrink the promised daily payout.
        BigDecimal total=holdings.stream().map(h->emissionAmount(series.priceUsdt(),rate)).reduce(BigDecimal.ZERO,BigDecimal::add);
        int inserted=mapper.insertEmissionBatch(new AppGenesisMapper.EmissionBatchWrite(batchNo,LocalDateTime.now(clock),rate,
                holdings.size(),money(total),AdminActorResolver.resolve("system"),request.reason().trim(),request.decisionRef().trim()));
        if(inserted==1){
            for(AppGenesisMapper.HoldingRow h:holdings){
                BigDecimal amount=emissionAmount(series.priceUsdt(),rate);
                if(amount.signum()>0) mapper.insertEmissionItem(new AppGenesisMapper.EmissionItemWrite(batchNo,h.holdingNo(),h.userId(),amount));
            }
        }
        int paid=0; BigDecimal paidTotal=BigDecimal.ZERO;
        for(AppGenesisMapper.EmissionItemRow item:mapper.lockPendingEmissionItems(batchNo)){
            BigDecimal before=mapper.lockWallet(item.userId());
            if(before==null || mapper.creditWallet(item.userId(),item.amountUsdt())!=1) throw new BizException(409,"GENESIS_EMISSION_WALLET_CONFLICT");
            String billNo="G4E-"+batchNo+"-"+item.holdingNo();
            if(mapper.insertLedger(new AppGenesisMapper.LedgerWrite(item.userId(),billNo,"GENESIS_EMISSION","IN",
                    item.amountUsdt(),money(before.add(item.amountUsdt())),"G4 Genesis emission batch "+batchNo))!=1)
                throw new BizException(409,"GENESIS_EMISSION_LEDGER_CONFLICT");
            LocalDateTime paidAt=LocalDateTime.now(clock);
            if(mapper.markEmissionPaid(item.id(),paidAt)!=1) throw new BizException(409,"GENESIS_EMISSION_STATE_CONFLICT");
            AppGenesisMapper.UserPolicyRow policy=mapper.userPolicy(item.userId());
            if(policy==null) throw new BizException(409,"GENESIS_EMISSION_USER_ATTRIBUTION_MISSING");
            outbox.publishUserEvent("GENESIS_HOLDING",item.holdingNo(),"genesis.dividend_paid",
                    item.userId(),policy.phase(),policy.accountAgeMonths(),policy.cohort(),linked(
                            "holdingNo",item.holdingNo(),"amountUsdt",item.amountUsdt(),
                            "rateApplied",rate,"paidAt",paidAt));
            paid++; paidTotal=paidTotal.add(item.amountUsdt());
        }
        mapper.completeEmissionBatch(batchNo);
        Map<String,Object> event=linked("batchNo",batchNo,"paidCount",paid,"totalAmountUsdt",money(paidTotal),"decisionRef",request.decisionRef().trim());
        outbox.publish("GENESIS_EMISSION_BATCH",batchNo,"admin.genesis_emission_batch_rerun",event);
        audit.recordRequired(AuditLogWriteRequest.builder().action("G4_GENESIS_EMISSION_BATCH_RERUN")
                .resourceType("GENESIS_EMISSION_BATCH").resourceId(batchNo).bizNo("G4E-"+batchNo)
                .actorType("ADMIN").actorUsername(AdminActorResolver.resolve(null)).method("POST")
                .path("/api/admin/market/nex/genesis/dividend-batches/"+batchNo+"/rerun")
                .result("SUCCESS").riskLevel("HIGH").detail(linked("idempotencyKey",idem,"reason",request.reason().trim(),"state",event)).build());
        Map<String,Object> response=marketService.genesisOverview().getData();
        response.put("updated",event);
        return ApiResult.ok(response);
    }

    private BigDecimal emissionAmount(BigDecimal base,BigDecimal rate){return money(nz(base).multiply(rate).divide(BigDecimal.valueOf(100),6,RoundingMode.HALF_UP));}
    private void requireCommand(String key,NexMarketValueUpdateRequest r){
        if(!StringUtils.hasText(key)) throw new BizException(428,"IDEMPOTENCY_KEY_REQUIRED");
        if(r==null) throw new BizException(422,"REQUEST_REQUIRED");
        requireText(r.reason(),"REASON_LENGTH_INVALID",8,200);
    }
    private void requireDecision(String value){requireText(value,"G4_DECISION_REF_REQUIRED",3,128);}
    private void requireText(String value,String code,int min,int max){if(!StringUtils.hasText(value)||value.trim().length()<min||value.trim().length()>max)throw new BizException(422,code);}
    private void requireSuccess(ApiResult<?> result){if(result==null||result.getCode()!=0)throw new BizException(result==null?500:result.getCode(),result==null?"G4_COMMAND_FAILED":result.getMessage());}
    @SuppressWarnings({"rawtypes","unchecked"}) private ApiResult<Map<String,Object>> once(String scope,String key,Object request,Supplier<ApiResult<Map<String,Object>>> action){return (ApiResult<Map<String,Object>>)(ApiResult)idempotency.execute("ADMIN:G4_"+scope,key,sha256(String.valueOf(request)),ApiResult.class,(Supplier)action);}
    private String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private BigDecimal money(BigDecimal v){return nz(v).setScale(6,RoundingMode.HALF_UP);} private BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private String text(String v,String fallback){return StringUtils.hasText(v)?v.trim():fallback;}
    private Map<String,Object> linked(Object...v){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
}
