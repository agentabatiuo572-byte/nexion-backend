package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.InviteRow;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.TierRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

@Service
@RequiredArgsConstructor
public class GenesisCatalogService {
    private static final Set<String> NOTICE_KEYS = Set.of("default", "phase_control", "maintenance", "compliance");
    private final GenesisCatalogMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final Clock clock;

    public ApiResult<Map<String,Object>> enrich(ApiResult<Map<String,Object>> base) {
        if (base == null || base.getCode() != 0 || base.getData() == null) return base;
        CatalogState state = requireState();
        Map<String,Object> data = new LinkedHashMap<>(base.getData());
        data.put("tiers", tierViews(mapper.activeTiers()));
        data.put("tiersVersion", state.tiersVersion());
        Map<String,Object> market = new LinkedHashMap<>((Map<String,Object>) data.getOrDefault("market", Map.of()));
        market.put("marketOpenState", state.marketOpenState());
        market.put("closedNoticeKey", state.closedNoticeKey());
        market.put("marketLastChange", state.lastChange());
        market.put("marketOpenStateVersion", state.marketOpenStateVersion());
        data.put("market", market);
        return ApiResult.ok(data);
    }

    public Map<String,Object> publicState() {
        CatalogState state = mapper.state();
        if (state == null || !Set.of("open", "closed").contains(state.marketOpenState())) {
            return unavailablePublicState("GENESIS_CATALOG_UNAVAILABLE");
        }
        try {
            List<TierRow> tiers = requireTiers();
            return linked("tiers", tierViews(tiers), "tiersVersion", state.tiersVersion(),
                    "marketOpenState", state.marketOpenState(),
                    "marketOpenStateVersion", state.marketOpenStateVersion(),
                    "closedNoticeKey", state.closedNoticeKey(), "catalogAvailable", true,
                    "tradeAvailable", "open".equals(state.marketOpenState()), "tradeBlockedReason",
                    "open".equals(state.marketOpenState()) ? "" : "GENESIS_MARKET_CLOSED");
        } catch (BizException ex) {
            return unavailablePublicState(ex.getMessage());
        }
    }

    public boolean marketOpen() {
        CatalogState state = mapper.state();
        return state != null && "open".equals(state.marketOpenState());
    }

    public BigDecimal priceForSold(long sold) {
        return mapper.activeTiers().stream()
                .filter(tier -> sold >= tier.rangeFrom() && sold < tier.rangeTo())
                .findFirst().map(TierRow::priceUsdt)
                .orElseThrow(() -> new BizException(409, "GENESIS_TIER_UNAVAILABLE"));
    }

    public ApiResult<Void> createTier(String idempotencyKey, TierRequest request) {
        return once("CREATE_TIER", idempotencyKey, request, () -> createTierOnce(request));
    }

    public ApiResult<Void> updateTier(String tierId, String idempotencyKey, TierRequest request) {
        return once("UPDATE_TIER:" + tierId, idempotencyKey, request, () -> updateTierOnce(tierId, request));
    }

    public ApiResult<Void> deleteTier(String tierId, String idempotencyKey, DeleteTierRequest request) {
        return once("DELETE_TIER:" + tierId, idempotencyKey, request, () -> deleteTierOnce(tierId, request));
    }

    public ApiResult<Void> updateMarketState(String idempotencyKey, MarketStateRequest request) {
        return once("MARKET_STATE", idempotencyKey, request, () -> updateMarketStateOnce(request));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> createTierOnce(TierRequest request) {
        requireReason(request == null ? null : request.reason());
        CatalogState state = lockVersion(request == null ? null : request.expectedTiersVersion());
        List<TierRow> tiers = requireTiers();
        TierRow last = tiers.get(tiers.size() - 1);
        validateBoundary(request.to(), request.priceUSDT(), last.rangeTo());
        String tierId = "t" + state.nextTierSeq();
        if (mapper.insertTier(new TierRow(tierId,last.rangeTo(),request.to(),request.priceUSDT())) != 1
                || mapper.advanceTierVersion(state.tiersVersion(), state.nextTierSeq() + 1) != 1) {
            throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");
        }
        audit("GENESIS_TIER_CREATED",tierId,request.operator(),request.reason(),Map.of("from",last.rangeTo(),"to",request.to(),"priceUSDT",request.priceUSDT()));
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> updateTierOnce(String tierId, TierRequest request) {
        requireReason(request == null ? null : request.reason());
        CatalogState state = lockVersion(request == null ? null : request.expectedTiersVersion());
        List<TierRow> tiers = requireTiers();
        int index = indexOf(tiers,tierId);
        TierRow current = tiers.get(index);
        validateBoundary(request.to(),request.priceUSDT(),current.rangeFrom());
        if (index + 1 < tiers.size() && request.to() >= tiers.get(index + 1).rangeTo()) {
            throw new BizException(422,"GENESIS_TIER_CROSSES_NEXT_RANGE");
        }
        if (index == tiers.size()-1 && request.to() < mapper.soldCount()) throw new BizException(422,"GENESIS_TIER_BELOW_SOLD");
        List<TierRow> candidate = new ArrayList<>(tiers);
        candidate.set(index, new TierRow(current.tierId(), current.rangeFrom(), request.to(), request.priceUSDT()));
        if (index + 1 < candidate.size()) {
            TierRow next = candidate.get(index + 1);
            candidate.set(index + 1, new TierRow(next.tierId(), request.to(), next.rangeTo(), next.priceUsdt()));
        }
        validateTiers(candidate);
        if (mapper.updateTier(tierId,request.to(),request.priceUSDT()) != 1) throw new BizException(409,"GENESIS_TIER_CONFLICT");
        if (index+1 < tiers.size() && mapper.updateTierFrom(tiers.get(index+1).tierId(),request.to()) != 1) throw new BizException(409,"GENESIS_TIER_CONFLICT");
        if (mapper.advanceTierVersion(state.tiersVersion(),state.nextTierSeq()) != 1) throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");
        audit("GENESIS_TIER_UPDATED",tierId,request.operator(),request.reason(),Map.of("to",request.to(),"priceUSDT",request.priceUSDT()));
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> deleteTierOnce(String tierId, DeleteTierRequest request) {
        requireReason(request == null ? null : request.reason());
        CatalogState state = lockVersion(request == null ? null : request.expectedTiersVersion());
        List<TierRow> tiers = requireTiers();
        if (tiers.size() <= 1) throw new BizException(422,"GENESIS_TIER_MIN_ONE");
        int index=indexOf(tiers,tierId); TierRow current=tiers.get(index);
        if (index==0) {
            if (mapper.updateTierFrom(tiers.get(1).tierId(),current.rangeFrom())!=1) throw new BizException(409,"GENESIS_TIER_CONFLICT");
        } else {
            if (mapper.updateTierTo(tiers.get(index-1).tierId(),current.rangeTo())!=1) throw new BizException(409,"GENESIS_TIER_CONFLICT");
        }
        if (mapper.softDeleteTier(tierId)!=1 || mapper.advanceTierVersion(state.tiersVersion(),state.nextTierSeq())!=1) throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");
        List<TierRow> after=mapper.activeTiers();
        if (after.get(after.size()-1).rangeTo()<mapper.soldCount()) throw new BizException(422,"GENESIS_TIER_BELOW_SOLD");
        audit("GENESIS_TIER_DELETED",tierId,request.operator(),request.reason(),Map.of("softDeleted",true));
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> updateMarketStateOnce(MarketStateRequest request) {
        requireReason(request==null?null:request.reason());
        if (request==null || !Set.of("open","closed").contains(request.value())) throw new BizException(422,"GENESIS_MARKET_STATE_INVALID");
        String notice=StringUtils.hasText(request.noticeKey())?request.noticeKey().trim():"default";
        if (!NOTICE_KEYS.contains(notice)) throw new BizException(422,"GENESIS_NOTICE_KEY_INVALID");
        CatalogState state=requireStateForUpdate();
        if (request.expectedMarketOpenStateVersion()==null
                || !request.expectedMarketOpenStateVersion().equals(state.marketOpenStateVersion())) {
            throw new BizException(409,"GENESIS_MARKET_STATE_VERSION_CONFLICT");
        }
        String actor=AdminActorResolver.resolve(request.operator());
        String change=LocalDateTime.now(clock)+" "+actor+" "+state.marketOpenState()+"->"+request.value()+":"+request.reason().trim();
        if (mapper.updateMarketState(request.value(),notice,change,state.marketOpenStateVersion())!=1) throw new BizException(409,"GENESIS_MARKET_STATE_CONFLICT");
        audit("GENESIS_MARKET_STATE_UPDATED","MARKET",actor,request.reason(),Map.of("before",state.marketOpenState(),"after",request.value(),"noticeKey",notice));
        return ApiResult.ok();
    }

    public ApiResult<Map<String,Object>> invites() { return ApiResult.ok(inviteRegistry(mapper.inviteCodes())); }

    public ApiResult<Map<String,Object>> issueInvites(String idempotencyKey, InviteIssueRequest request) {
        return onceResult("ISSUE_INVITES",idempotencyKey,request,()->issueInvitesOnce(request));
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Map<String,Object>> issueInvitesOnce(InviteIssueRequest request) {
        if (request==null || request.count()==null || request.count()<1 || request.count()>100
                || request.note()!=null && request.note().trim().length()>60) throw new BizException(422,"GENESIS_INVITE_REQUEST_INVALID");
        String actor=AdminActorResolver.resolve(request.operator()); List<Map<String,Object>> issued=new ArrayList<>();
        for(int i=0;i<request.count();i++){String code="NEXGRID-OG-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            if(mapper.insertInvite(code,actor,request.note()==null?"":request.note().trim())!=1) throw new BizException(409,"GENESIS_INVITE_CREATE_CONFLICT"); issued.add(Map.of("code",code,"status","unused"));}
        audit("GENESIS_INVITES_ISSUED","BATCH",actor,"issue invite codes",Map.of("count",request.count(),"note",request.note()==null?"":request.note()));
        Map<String,Object> data=new LinkedHashMap<>(); data.put("issued",issued); data.put("registry",inviteRegistry(mapper.inviteCodes())); return ApiResult.ok(data);
    }

    public ApiResult<Map<String,Object>> voidInvite(String code,String idempotencyKey,InviteVoidRequest request){return onceResult("VOID_INVITE:"+code,idempotencyKey,request,()->voidInviteOnce(code,request));}
    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Map<String,Object>> voidInviteOnce(String code,InviteVoidRequest request){requireReason(request==null?null:request.reason()); InviteRow row=mapper.lockInvite(normalizeCode(code));
        if(row==null) throw new BizException(404,"GENESIS_INVITE_NOT_FOUND"); if(!"unused".equals(row.status())) throw new BizException(409,"GENESIS_INVITE_STATE_CONFLICT"); String actor=AdminActorResolver.resolve(request.operator());
        if(mapper.voidInvite(row.code(),actor,request.reason().trim())!=1) throw new BizException(409,"GENESIS_INVITE_STATE_CONFLICT"); audit("GENESIS_INVITE_VOIDED",row.code(),actor,request.reason(),Map.of("before","unused","after","void")); return invites();}

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> redeem(Long userId,String code){if(userId==null||userId<=0)throw new BizException(403,"USER_SUBJECT_REQUIRED"); InviteRow row=mapper.lockInvite(normalizeCode(code));
        if(row==null)throw new BizException(404,"GENESIS_INVITE_NOT_FOUND");
        if("used".equals(row.status()) && userId.equals(row.redeemedBy())) return ApiResult.ok(Map.of("code",row.code(),"status","used","replayed",true));
        if(mapper.redeemedCount(userId)>0)throw new BizException(409,"GENESIS_INVITE_ACCOUNT_ALREADY_REDEEMED");
        if("void".equals(row.status())) throw new BizException(409,"GENESIS_INVITE_VOIDED");
        if(!"unused".equals(row.status()))throw new BizException(409,"GENESIS_INVITE_STATE_CONFLICT");
        try {
            if(mapper.redeemInvite(row.code(),userId)!=1)throw new BizException(409,"GENESIS_INVITE_STATE_CONFLICT");
        } catch (DuplicateKeyException ex) {
            // redeemInvite can only collide on the one-account-one-redemption key.
            // Normalize the concurrent loser instead of leaking a generic database 500.
            throw new BizException(409,"GENESIS_INVITE_ACCOUNT_ALREADY_REDEEMED");
        }
        audit("GENESIS_INVITE_REDEEMED",row.code(),"user:"+userId,"redeem invite",Map.of("userId",userId)); return ApiResult.ok(Map.of("code",row.code(),"status","used","replayed",false));}

    public boolean hasRedeemedInvite(Long userId) {
        return userId != null && userId > 0 && mapper.redeemedCount(userId) > 0;
    }

    private CatalogState lockVersion(Long expected){if(expected==null)throw new BizException(400,"EXPECTED_TIERS_VERSION_REQUIRED");CatalogState state=requireStateForUpdate();if(!expected.equals(state.tiersVersion()))throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");return state;}
    private CatalogState requireState(){CatalogState s=mapper.state();if(s==null||!Set.of("open","closed").contains(s.marketOpenState()))throw new BizException(503,"GENESIS_CATALOG_UNAVAILABLE");return s;}
    private CatalogState requireStateForUpdate(){CatalogState s=mapper.lockState();if(s==null)throw new BizException(503,"GENESIS_CATALOG_UNAVAILABLE");return s;}
    private List<TierRow> requireTiers(){List<TierRow> rows=mapper.activeTiers();if(rows==null||rows.isEmpty())throw new BizException(503,"GENESIS_TIERS_UNAVAILABLE");validateTiers(rows);return rows;}
    private void validateTiers(List<TierRow> rows){int next=0;for(TierRow row:rows){if(row.rangeFrom()!=next||row.rangeTo()<=row.rangeFrom()||row.priceUsdt()==null||row.priceUsdt().signum()<=0)throw new BizException(503,"GENESIS_TIERS_INVALID");next=row.rangeTo();}if(next<mapper.soldCount())throw new BizException(503,"GENESIS_TIERS_BELOW_SOLD");}
    private void validateBoundary(Integer to,BigDecimal price,int from){if(to==null||to<=from||price==null||price.signum()<=0||price.stripTrailingZeros().scale()>0)throw new BizException(422,"GENESIS_TIER_INVALID");}
    private int indexOf(List<TierRow> rows,String id){for(int i=0;i<rows.size();i++)if(rows.get(i).tierId().equals(id))return i;throw new BizException(404,"GENESIS_TIER_NOT_FOUND");}
    private List<Map<String,Object>> tierViews(List<TierRow> rows){return rows.stream().map(r->Map.<String,Object>of("id",r.tierId(),"from",r.rangeFrom(),"to",r.rangeTo(),"priceUSDT",r.priceUsdt())).toList();}
    private Map<String,Object> inviteRegistry(List<InviteRow> rows){List<Map<String,Object>> list=rows.stream().map(r->{Map<String,Object> m=new LinkedHashMap<>();m.put("code",r.code());m.put("status",r.status());m.put("issuedBy",r.issuedBy());m.put("issuedAt",epoch(r.issuedAt()));m.put("note",r.note());m.put("redeemedBy",r.redeemedBy());m.put("redeemedAt",epoch(r.redeemedAt()));m.put("voidedBy",r.voidedBy());m.put("voidedAt",epoch(r.voidedAt()));m.put("voidReason",r.voidReason());return m;}).toList(); Map<String,Object> counts=new LinkedHashMap<>();counts.put("all",rows.size());counts.put("unused",rows.stream().filter(r->"unused".equals(r.status())).count());counts.put("used",rows.stream().filter(r->"used".equals(r.status())).count());counts.put("void",rows.stream().filter(r->"void".equals(r.status())).count());return Map.of("codes",list,"counts",counts);}
    private Long epoch(LocalDateTime value){return value==null?null:value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
    private String normalizeCode(String code){String v=code==null?"":code.trim().toUpperCase();if(!v.matches("NEXGRID-OG-[A-Z0-9]{16}"))throw new BizException(422,"GENESIS_INVITE_CODE_INVALID");return v;}
    private void requireReason(String reason){if(!StringUtils.hasText(reason)||reason.trim().length()<8||reason.trim().length()>200)throw new BizException(422,"REASON_INVALID");}
    private void audit(String action,String id,String actor,String reason,Map<String,Object> detail){Map<String,Object> d=new LinkedHashMap<>(detail);d.put("reason",reason);audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("GENESIS_CATALOG").resourceId(id).actorUsername(AdminActorResolver.resolve(actor)).riskLevel("HIGH").detail(d).build());}
    @SuppressWarnings({"rawtypes","unchecked"}) private ApiResult<Void> once(String action,String key,Object req,Supplier<ApiResult<Void>> supplier){return (ApiResult<Void>)(ApiResult)idempotency.execute("G4_"+action,key,hash(String.valueOf(req)),ApiResult.class,(Supplier)supplier);}
    @SuppressWarnings({"rawtypes","unchecked"}) private ApiResult<Map<String,Object>> onceResult(String action,String key,Object req,Supplier<ApiResult<Map<String,Object>>> supplier){return (ApiResult<Map<String,Object>>)(ApiResult)idempotency.execute("G4_"+action,key,hash(String.valueOf(req)),ApiResult.class,(Supplier)supplier);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private Map<String,Object> unavailablePublicState(String reason) {
        CatalogState state = mapper.state();
        return linked("tiers", List.of(), "tiersVersion", state == null ? 0L : state.tiersVersion(),
                "marketOpenState", "closed", "marketOpenStateVersion",
                state == null ? 0L : state.marketOpenStateVersion(), "closedNoticeKey", "maintenance",
                "catalogAvailable", false, "tradeAvailable", false, "tradeBlockedReason", reason);
    }

    private Map<String,Object> linked(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record TierRequest(Integer to,BigDecimal priceUSDT,Long expectedTiersVersion,String reason,String operator){}
    public record DeleteTierRequest(Long expectedTiersVersion,String reason,String operator){}
    public record MarketStateRequest(String value,String reason,String operator,String noticeKey,
                                     Long expectedMarketOpenStateVersion){}
    public record InviteIssueRequest(Integer count,String note,String operator){}
    public record InviteVoidRequest(String reason,String operator){}
}
