package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.SeriesBootstrapRow;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.SeriesRow;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        CatalogState state = mapper.state();
        CatalogReadiness readiness = state == null || !Set.of("open", "closed").contains(state.marketOpenState())
                ? new CatalogReadiness(false,"GENESIS_CATALOG_UNAVAILABLE",false) : readiness();
        Map<String,Object> data = new LinkedHashMap<>(base.getData());
        List<TierRow> tiers = mapper.activeTiers();
        data.put("tiers", tierViews(tiers == null ? List.of() : tiers));
        data.put("tiersVersion", state == null ? 0L : state.tiersVersion());
        data.put("catalogAvailable", readiness.available());
        data.put("seriesSetupRequired", readiness.seriesSetupRequired());
        Map<String,Object> market = new LinkedHashMap<>((Map<String,Object>) data.getOrDefault("market", Map.of()));
        boolean configuredOpen = state != null && "open".equals(state.marketOpenState());
        boolean killSwitchOpen = Boolean.TRUE.equals(market.get("enabled"));
        data.put("tradeAvailable", readiness.available() && configuredOpen && killSwitchOpen);
        data.put("tradeBlockedReason", !readiness.available() ? readiness.reason()
                : !configuredOpen ? "GENESIS_MARKET_CLOSED" : !killSwitchOpen ? "GENESIS_MARKET_PAUSED" : "");
        market.put("marketOpenState", readiness.available() && configuredOpen ? "open" : "closed");
        market.put("configuredMarketOpenState", state == null ? "closed" : state.marketOpenState());
        market.put("closedNoticeKey", state == null ? "maintenance" : state.closedNoticeKey());
        market.put("marketLastChange", state == null ? "" : state.lastChange());
        market.put("marketOpenStateVersion", state == null ? 0L : state.marketOpenStateVersion());
        market.put("prerequisiteStatus", readiness.reason());
        market.put("seriesSetupRequired", readiness.seriesSetupRequired());
        data.put("market", market);
        return ApiResult.ok(data);
    }

    public Map<String,Object> publicState() {
        CatalogState state = mapper.state();
        if (state == null || !Set.of("open", "closed").contains(state.marketOpenState())) {
            return unavailablePublicState("GENESIS_CATALOG_UNAVAILABLE");
        }
        CatalogReadiness readiness = readiness();
        if (readiness.available()) {
            List<TierRow> tiers = mapper.activeTiers();
            return linked("tiers", tierViews(tiers), "tiersVersion", state.tiersVersion(),
                    "marketOpenState", state.marketOpenState(),
                    "marketOpenStateVersion", state.marketOpenStateVersion(),
                    "closedNoticeKey", state.closedNoticeKey(), "catalogAvailable", true,
                    "tradeAvailable", "open".equals(state.marketOpenState()), "tradeBlockedReason",
                    "open".equals(state.marketOpenState()) ? "" : "GENESIS_MARKET_CLOSED",
                    "seriesSetupRequired", false);
        }
        return unavailablePublicState(readiness.reason());
    }

    public boolean marketOpen() {
        Map<String,Object> state = publicState();
        return Boolean.TRUE.equals(state.get("catalogAvailable")) && "open".equals(state.get("marketOpenState"));
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

    public ApiResult<Void> initializeSeries(String idempotencyKey, SeriesBootstrapRequest request) {
        return once("INITIALIZE_SERIES", idempotencyKey, request, () -> initializeSeriesOnce(request));
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
        if ("open".equals(request.value())) requireReadyForOpen();
        String actor=AdminActorResolver.resolve(request.operator());
        String change=LocalDateTime.now(clock)+" "+actor+" "+state.marketOpenState()+"->"+request.value()+":"+request.reason().trim();
        if (mapper.updateMarketState(request.value(),notice,change,state.marketOpenStateVersion())!=1) throw new BizException(409,"GENESIS_MARKET_STATE_CONFLICT");
        audit("GENESIS_MARKET_STATE_UPDATED","MARKET",actor,request.reason(),Map.of("before",state.marketOpenState(),"after",request.value(),"noticeKey",notice));
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> initializeSeriesOnce(SeriesBootstrapRequest request) {
        requireReason(request == null ? null : request.reason());
        if (request == null || !StringUtils.hasText(request.seriesCode())
                || !request.seriesCode().trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new BizException(422,"GENESIS_SERIES_CODE_INVALID");
        }
        requireText(request.name(),"GENESIS_SERIES_NAME_INVALID",2,128);
        if (request.royaltyBps() == null || request.royaltyBps() < 0 || request.royaltyBps() > 10000) {
            throw new BizException(422,"GENESIS_SERIES_ROYALTY_INVALID");
        }
        if (request.dailyEmissionRatePct() == null || request.dailyEmissionRatePct().signum() < 0
                || request.dailyEmissionRatePct().compareTo(new BigDecimal("100")) > 0
                || request.dailyEmissionRatePct().stripTrailingZeros().scale() > 6) {
            throw new BizException(422,"GENESIS_SERIES_EMISSION_RATE_INVALID");
        }
        String formula = request.dividendBaseFormula() == null ? "" : request.dividendBaseFormula().trim();
        if (formula.length() > 255) throw new BizException(422,"GENESIS_SERIES_DIVIDEND_FORMULA_INVALID");
        CatalogState state = requireStateForUpdate();
        if (mapper.activeSeriesCount() != 0) throw new BizException(409,"GENESIS_ACTIVE_SERIES_ALREADY_EXISTS");
        if (mapper.soldCount() != 0) throw new BizException(409,"GENESIS_SERIES_INIT_EXISTING_HOLDINGS");
        String seriesCode = request.seriesCode().trim();
        if (mapper.seriesCodeCount(seriesCode) != 0) throw new BizException(409,"GENESIS_SERIES_CODE_CONFLICT");
        List<TierRow> tiers = requireTiers();
        TierRow first = tiers.get(0);
        TierRow last = tiers.get(tiers.size() - 1);
        SeriesBootstrapRow row = new SeriesBootstrapRow(seriesCode,request.name().trim(),last.rangeTo(),first.priceUsdt(),
                request.royaltyBps(),request.dailyEmissionRatePct(),formula);
        if (mapper.insertActiveSeries(row) != 1) throw new BizException(409,"GENESIS_SERIES_INIT_CONFLICT");
        String actor = AdminActorResolver.resolve(request.operator());
        String change = LocalDateTime.now(clock)+" "+actor+" "+state.marketOpenState()
                +"->closed:series initialized; explicit reopen required";
        if (mapper.updateMarketState("closed","default",change,state.marketOpenStateVersion()) != 1) {
            throw new BizException(409,"GENESIS_MARKET_STATE_CONFLICT");
        }
        audit("GENESIS_SERIES_INITIALIZED",seriesCode,actor,request.reason(),linked(
                "seriesCode",seriesCode,"name",request.name().trim(),"totalSupply",last.rangeTo(),
                "openingPriceUSDT",first.priceUsdt(),"marketState","closed"));
        return ApiResult.ok();
    }

    private CatalogState lockVersion(Long expected){if(expected==null)throw new BizException(400,"EXPECTED_TIERS_VERSION_REQUIRED");CatalogState state=requireStateForUpdate();if(!expected.equals(state.tiersVersion()))throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");return state;}
    private CatalogState requireStateForUpdate(){CatalogState s=mapper.lockState();if(s==null||!Set.of("open","closed").contains(s.marketOpenState())||s.marketOpenStateVersion()==null)throw new BizException(503,"GENESIS_CATALOG_UNAVAILABLE");return s;}
    private List<TierRow> requireTiers(){List<TierRow> rows=mapper.activeTiers();if(rows==null||rows.isEmpty())throw new BizException(503,"GENESIS_TIERS_UNAVAILABLE");validateTiers(rows);return rows;}
    private void validateTiers(List<TierRow> rows){int next=0;for(TierRow row:rows){if(row.rangeFrom()!=next||row.rangeTo()<=row.rangeFrom()||row.priceUsdt()==null||row.priceUsdt().signum()<=0)throw new BizException(503,"GENESIS_TIERS_INVALID");next=row.rangeTo();}if(next<mapper.soldCount())throw new BizException(503,"GENESIS_TIERS_BELOW_SOLD");}
    private CatalogReadiness readiness(){
        long activeCount=mapper.activeSeriesCount();
        if(activeCount==0)return new CatalogReadiness(false,"GENESIS_SERIES_UNAVAILABLE",true);
        if(activeCount!=1)return new CatalogReadiness(false,"GENESIS_ACTIVE_SERIES_AMBIGUOUS",false);
        SeriesRow series=mapper.activeSeries();
        if(series==null||series.totalSupply()==null||series.totalSupply()<=0||series.priceUsdt()==null||series.priceUsdt().signum()<=0)
            return new CatalogReadiness(false,"GENESIS_SERIES_INVALID",false);
        try{
            List<TierRow> tiers=requireTiers();
            if(!Objects.equals(tiers.get(tiers.size()-1).rangeTo(),series.totalSupply()))
                return new CatalogReadiness(false,"GENESIS_TIERS_SUPPLY_MISMATCH",false);
            long sold=mapper.soldCount();
            if(sold<series.totalSupply()&&tiers.stream().noneMatch(t->sold>=t.rangeFrom()&&sold<t.rangeTo()))
                return new CatalogReadiness(false,"GENESIS_TIER_QUOTE_UNAVAILABLE",false);
            return new CatalogReadiness(true,"READY",false);
        }catch(BizException ex){return new CatalogReadiness(false,ex.getMessage(),false);}
    }
    private void requireReadyForOpen(){CatalogReadiness readiness=readiness();if(!readiness.available())throw new BizException(409,readiness.reason());}
    private void validateBoundary(Integer to,BigDecimal price,int from){if(to==null||to<=from||price==null||price.signum()<=0||price.stripTrailingZeros().scale()>0)throw new BizException(422,"GENESIS_TIER_INVALID");}
    private int indexOf(List<TierRow> rows,String id){for(int i=0;i<rows.size();i++)if(rows.get(i).tierId().equals(id))return i;throw new BizException(404,"GENESIS_TIER_NOT_FOUND");}
    private List<Map<String,Object>> tierViews(List<TierRow> rows){return rows.stream().map(r->Map.<String,Object>of("id",r.tierId(),"from",r.rangeFrom(),"to",r.rangeTo(),"priceUSDT",r.priceUsdt())).toList();}
    private void requireReason(String reason){if(!StringUtils.hasText(reason)||reason.trim().length()<8||reason.trim().length()>200)throw new BizException(422,"REASON_INVALID");}
    private void requireText(String value,String code,int min,int max){if(!StringUtils.hasText(value)||value.trim().length()<min||value.trim().length()>max)throw new BizException(422,code);}
    private void audit(String action,String id,String actor,String reason,Map<String,Object> detail){Map<String,Object> d=new LinkedHashMap<>(detail);d.put("reason",reason);audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("GENESIS_CATALOG").resourceId(id).actorUsername(AdminActorResolver.resolve(actor)).riskLevel("HIGH").detail(d).build());}
    @SuppressWarnings({"rawtypes","unchecked"}) private ApiResult<Void> once(String action,String key,Object req,Supplier<ApiResult<Void>> supplier){return (ApiResult<Void>)(ApiResult)idempotency.execute("G4_"+action,key,hash(String.valueOf(req)),ApiResult.class,(Supplier)supplier);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private Map<String,Object> unavailablePublicState(String reason) {
        CatalogState state = mapper.state();
        return linked("tiers", List.of(), "tiersVersion", state == null ? 0L : state.tiersVersion(),
                "marketOpenState", "closed", "marketOpenStateVersion",
                state == null ? 0L : state.marketOpenStateVersion(), "closedNoticeKey", "maintenance",
                "catalogAvailable", false, "tradeAvailable", false, "tradeBlockedReason", reason,
                "seriesSetupRequired", "GENESIS_SERIES_UNAVAILABLE".equals(reason));
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
    public record SeriesBootstrapRequest(String seriesCode,String name,Integer royaltyBps,
                                         BigDecimal dailyEmissionRatePct,String dividendBaseFormula,
                                         String reason,String operator){}
    private record CatalogReadiness(boolean available,String reason,boolean seriesSetupRequired){}
}
