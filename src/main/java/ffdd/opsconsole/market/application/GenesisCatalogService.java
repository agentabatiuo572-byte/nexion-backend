package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.CatalogState;
import ffdd.opsconsole.market.mapper.GenesisCatalogMapper.LockedSeriesRow;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class GenesisCatalogService {
    private static final Set<String> NOTICE_KEYS = Set.of("default", "phase_control", "maintenance", "compliance");
    private static final int SAFE_INITIAL_ROYALTY_BPS = 0;
    private static final BigDecimal SAFE_INITIAL_DAILY_EMISSION_RATE_PCT = BigDecimal.ZERO;
    private static final String SAFE_INITIAL_DIVIDEND_BASE_FORMULA = "";
    private static final BigDecimal TIER_PRICE_LIMIT = new BigDecimal("1000000000000");
    private final GenesisCatalogMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final Clock clock;
    @Autowired
    private OpsNexMarketService marketService;
    @Autowired
    private AppGenesisMapper appGenesisMapper;

    public ApiResult<Map<String,Object>> enrich(ApiResult<Map<String,Object>> base) {
        if (base == null || base.getCode() != 0 || base.getData() == null) return base;
        CatalogState state = mapper.state();
        CatalogReadiness readiness = state == null || !Set.of("open", "closed").contains(state.marketOpenState())
                ? new CatalogReadiness(false,"GENESIS_CATALOG_UNAVAILABLE",false,false,false) : readiness();
        Map<String,Object> data = new LinkedHashMap<>(base.getData());
        List<TierRow> tiers = mapper.activeTiers();
        data.put("tiers", tierViews(tiers == null ? List.of() : tiers));
        data.put("tiersVersion", state == null ? 0L : state.tiersVersion());
        data.put("catalogAvailable", readiness.available());
        data.put("seriesSetupRequired", readiness.seriesSetupRequired());
        data.put("seriesInitializationAvailable", readiness.seriesInitializationAvailable());
        data.put("seriesRecoveryRequired", readiness.seriesRecoveryRequired());
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
        market.put("seriesInitializationAvailable", readiness.seriesInitializationAvailable());
        market.put("seriesRecoveryRequired", readiness.seriesRecoveryRequired());
        data.put("market", market);
        return ApiResult.ok(data);
    }

    public Map<String,Object> publicState() {
        CatalogState state = mapper.state();
        if (state == null || !Set.of("open", "closed").contains(state.marketOpenState())) {
            return unavailablePublicState(new CatalogReadiness(false,"GENESIS_CATALOG_UNAVAILABLE",false,false,false));
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
                    "seriesSetupRequired", false, "seriesInitializationAvailable", false,
                    "seriesRecoveryRequired", false);
        }
        return unavailablePublicState(readiness);
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
        return changeTierDirectly(null, request);
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> updateTierOnce(String tierId, TierRequest request) {
        if (tierId == null) throw new BizException(404, "GENESIS_TIER_NOT_FOUND");
        return changeTierDirectly(tierId, request);
    }

    private ApiResult<Void> changeTierDirectly(String tierId, TierRequest request) {
        TierChange change = prepareTierChange(tierId, request);
        if (change.amplifies()) throw new BizException(409, "G4_CATALOG_APPROVAL_REQUIRED");
        return applyTierChange(change, request.reason(), request.operator(), null);
    }

    // The direct command and A2 both run in their READ_COMMITTED idempotency transaction.
    TierChange prepareTierChange(String tierId, TierRequest request) {
        requireReason(request == null ? null : request.reason());
        CatalogState state = lockVersion(request.expectedTiersVersion());
        LockedSeriesRow series = lockCurrentSeries();
        List<TierRow> before = mapper.activeTiers();
        long sold = mapper.soldCount();
        boolean create = tierId == null;
        if (before.isEmpty()) {
            if (!create) throw new BizException(503, "GENESIS_TIERS_UNAVAILABLE");
            if (sold != 0) throw new BizException(409, "GENESIS_SERIES_RECOVERY_REQUIRED");
        } else {
            validateTiers(before, sold);
        }

        int index = create ? before.size() : indexOf(before, tierId);
        TierRow current = create ? null : before.get(index);
        int from = create ? (before.isEmpty() ? 0 : before.get(index - 1).rangeTo()) : current.rangeFrom();
        validateBoundary(request.to(), request.priceUSDT(), from);
        TierRow next = index + 1 < before.size() ? before.get(index + 1) : null;
        if (next != null && request.to() >= next.rangeTo()) {
            throw new BizException(422,"GENESIS_TIER_CROSSES_NEXT_RANGE");
        }
        if (next == null && request.to() < sold) throw new BizException(422, "GENESIS_TIER_BELOW_SOLD");
        TierRow proposed = new TierRow(create ? "t" + state.nextTierSeq() : tierId, from, request.to(), request.priceUSDT());
        List<TierRow> after = new ArrayList<>(before);
        if (create) {
            after.add(proposed);
        } else {
            after.set(index, proposed);
            if (next != null) after.set(index + 1, new TierRow(next.tierId(), request.to(), next.rangeTo(), next.priceUsdt()));
        }
        validateTiers(after, sold);

        Integer newSupply = null;
        if (series != null && next == null && (create || !request.to().equals(current.rangeTo()))) {
            if (request.to() < Math.max(series.soldSupply(), sold)) {
                throw new BizException(422, "GENESIS_TIER_BELOW_SOLD");
            }
            if (request.to() != series.totalSupply()) {
                marketService.requireGenesisTierSupplyChange(request.to());
                newSupply = request.to();
            }
        }
        boolean amplifies = false;
        if (series != null) {
            if (newSupply != null && newSupply > series.totalSupply()) {
                amplifies = true;
            } else if (!create) {
                long quotePosition = appGenesisMapper.holdingCount(series.seriesCode());
                int commonSupply = newSupply == null ? series.totalSupply() : newSupply;
                amplifies = raisesUnsoldPrice(before, after, quotePosition, commonSupply);
            }
        }
        return new TierChange(state, series, before, after, index, newSupply, amplifies);
    }

    private LockedSeriesRow lockCurrentSeries() {
        // Primary purchases take the same series row lock before advancing their holding count.
        List<LockedSeriesRow> rows = mapper.lockActiveSeriesForSupply();
        if (rows.size() > 1) throw new BizException(409, "GENESIS_ACTIVE_SERIES_AMBIGUOUS");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean raisesUnsoldPrice(List<TierRow> before, List<TierRow> after, long sold, int commonSupply) {
        int oldIndex = 0;
        int newIndex = 0;
        while (oldIndex < before.size() && newIndex < after.size()) {
            TierRow oldTier = before.get(oldIndex);
            TierRow newTier = after.get(newIndex);
            long from = Math.max(sold, Math.max(oldTier.rangeFrom(), newTier.rangeFrom()));
            int to = Math.min(oldTier.rangeTo(), newTier.rangeTo());
            if (from < Math.min(to, commonSupply) && newTier.priceUsdt().compareTo(oldTier.priceUsdt()) > 0) return true;
            if (to >= commonSupply) break;
            if (oldTier.rangeTo() == to) oldIndex++;
            if (newTier.rangeTo() == to) newIndex++;
        }
        return false;
    }

    String tierSourceRevision(TierChange change) {
        CatalogState state = change.state();
        LockedSeriesRow series = change.series();
        StringBuilder source = new StringBuilder().append(state.tiersVersion()).append('/').append(state.nextTierSeq());
        if (series != null) {
            source.append('/').append(series.id()).append('/').append(series.totalSupply()).append('/').append(series.seriesCode());
        }
        for (TierRow tier : change.before()) {
            source.append('|').append(tier.tierId().length()).append(':').append(tier.tierId()).append(':')
                    .append(tier.rangeFrom()).append(':').append(tier.rangeTo()).append(':').append(tier.priceUsdt());
        }
        return hash(source.toString());
    }

    ApiResult<Void> applyTierChange(TierChange change, String reason, String operator, String operationId) {
        if (change.deletesTier()) return applyTierDeletion(change, reason, operator, operationId);
        CatalogState state = change.state();
        TierRow tier = change.after().get(change.index());
        Map<String,Object> detail = linked("from", tier.rangeFrom(), "to", tier.rangeTo(), "priceUSDT", tier.priceUsdt());
        if (change.newSupply() != null) {
            if (mapper.updateSeriesSupply(change.series().id(), change.newSupply()) != 1) {
                throw new BizException(409, "GENESIS_SUPPLY_CONFLICT");
            }
            detail.put("totalSupplyBefore", change.series().totalSupply());
            detail.put("totalSupplyAfter", change.newSupply());
        }
        boolean create = change.createsTier();
        if (create) {
            if (mapper.insertTier(tier) != 1) throw new BizException(409, "GENESIS_TIERS_VERSION_CONFLICT");
        } else {
            if (mapper.updateTier(tier.tierId(), tier.rangeTo(), tier.priceUsdt()) != 1) {
                throw new BizException(409, "GENESIS_TIER_CONFLICT");
            }
            if (change.index() + 1 < change.after().size()) {
                TierRow next = change.after().get(change.index() + 1);
                if (mapper.updateTierFrom(next.tierId(), next.rangeFrom()) != 1) {
                    throw new BizException(409, "GENESIS_TIER_CONFLICT");
                }
            }
        }
        if (mapper.advanceTierVersion(state.tiersVersion(), state.nextTierSeq() + (create ? 1 : 0)) != 1) {
            throw new BizException(409, "GENESIS_TIERS_VERSION_CONFLICT");
        }
        if (change.before().isEmpty()) {
            String actor = AdminActorResolver.resolve(operator);
            String description = LocalDateTime.now(clock)+" "+actor+" "+state.marketOpenState()
                    +"->closed:first tier created; explicit reopen required";
            if (mapper.updateMarketState("closed", "default", description, state.marketOpenStateVersion()) != 1) {
                throw new BizException(409, "GENESIS_MARKET_STATE_CONFLICT");
            }
            detail.put("marketStateBefore", state.marketOpenState());
            detail.put("marketState", "closed");
            detail.put("marketOpenStateVersion", state.marketOpenStateVersion() + 1);
            detail.put("marketForcedClosed", true);
        }
        if (operationId != null) detail.put("operationId", operationId);
        audit(create ? "GENESIS_TIER_CREATED" : "GENESIS_TIER_UPDATED", tier.tierId(), operator, reason, detail);
        return ApiResult.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    protected ApiResult<Void> deleteTierOnce(String tierId, DeleteTierRequest request) {
        TierChange change = prepareTierDeletion(tierId, request);
        if (change.amplifies()) throw new BizException(409, "G4_CATALOG_APPROVAL_REQUIRED");
        return applyTierChange(change, request.reason(), request.operator(), null);
    }

    TierChange prepareTierDeletion(String tierId, DeleteTierRequest request) {
        requireReason(request == null ? null : request.reason());
        CatalogState state = lockVersion(request.expectedTiersVersion());
        LockedSeriesRow series = lockCurrentSeries();
        List<TierRow> before = requireTiers();
        if (before.size() <= 1) throw new BizException(422, "GENESIS_TIER_MIN_ONE");
        int index = indexOf(before, tierId);
        List<TierRow> after = new ArrayList<>(before);
        TierRow deleted = after.remove(index);
        int recipientIndex = Math.max(0, index - 1);
        TierRow recipient = after.get(recipientIndex);
        after.set(recipientIndex, new TierRow(recipient.tierId(),
                Math.min(deleted.rangeFrom(), recipient.rangeFrom()),
                Math.max(deleted.rangeTo(), recipient.rangeTo()), recipient.priceUsdt()));
        if (after.get(after.size() - 1).rangeTo() < mapper.soldCount()) {
            throw new BizException(422, "GENESIS_TIER_BELOW_SOLD");
        }
        boolean amplifies = series != null && raisesUnsoldPrice(before, after,
                appGenesisMapper.holdingCount(series.seriesCode()), series.totalSupply());
        return new TierChange(state, series, before, after, index, null, amplifies);
    }

    private ApiResult<Void> applyTierDeletion(TierChange change, String reason, String operator, String operationId) {
        CatalogState state = change.state();
        String deleted = change.before().get(change.index()).tierId();
        TierRow recipient = change.deletionRecipient();
        int updated = change.index() == 0 ? mapper.updateTierFrom(recipient.tierId(), recipient.rangeFrom())
                : mapper.updateTierTo(recipient.tierId(), recipient.rangeTo());
        if (updated != 1) throw new BizException(409, "GENESIS_TIER_CONFLICT");
        if (mapper.softDeleteTier(deleted) != 1 || mapper.advanceTierVersion(state.tiersVersion(), state.nextTierSeq()) != 1) {
            throw new BizException(409, "GENESIS_TIERS_VERSION_CONFLICT");
        }
        Map<String,Object> detail = linked("softDeleted", true);
        if (operationId != null) detail.put("operationId", operationId);
        audit("GENESIS_TIER_DELETED", deleted, operator, reason, detail);
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
        if ("open".equals(request.value())) {
            requireReadyForOpen();
            if ("closed".equals(state.marketOpenState())) {
                ApiResult<Map<String, Object>> failure = marketService.coverageRedlineFailure();
                if (failure != null) throw new BizException(failure.getCode(), failure.getMessage());
            }
        }
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
        CatalogState state = requireStateForUpdate();
        if (mapper.activeSeriesCount() != 0) throw new BizException(409,"GENESIS_ACTIVE_SERIES_ALREADY_EXISTS");
        if (mapper.soldCount() != 0) throw new BizException(409,"GENESIS_SERIES_RECOVERY_REQUIRED");
        String seriesCode = request.seriesCode().trim();
        if (mapper.seriesCodeCount(seriesCode) != 0) throw new BizException(409,"GENESIS_SERIES_CODE_CONFLICT");
        List<TierRow> tiers = requireTiers();
        TierRow first = tiers.get(0);
        TierRow last = tiers.get(tiers.size() - 1);
        SeriesBootstrapRow row = new SeriesBootstrapRow(seriesCode,request.name().trim(),last.rangeTo(),first.priceUsdt(),
                SAFE_INITIAL_ROYALTY_BPS,SAFE_INITIAL_DAILY_EMISSION_RATE_PCT,SAFE_INITIAL_DIVIDEND_BASE_FORMULA);
        if (mapper.insertActiveSeries(row) != 1) throw new BizException(409,"GENESIS_SERIES_INIT_CONFLICT");
        String actor = AdminActorResolver.resolve(request.operator());
        String change = LocalDateTime.now(clock)+" "+actor+" "+state.marketOpenState()
                +"->closed:series initialized; explicit reopen required";
        if (mapper.updateMarketState("closed","default",change,state.marketOpenStateVersion()) != 1) {
            throw new BizException(409,"GENESIS_MARKET_STATE_CONFLICT");
        }
        audit("GENESIS_SERIES_INITIALIZED",seriesCode,actor,request.reason(),linked(
                "seriesCode",seriesCode,"name",request.name().trim(),"totalSupply",last.rangeTo(),
                "openingPriceUSDT",first.priceUsdt(),"royaltyBps",SAFE_INITIAL_ROYALTY_BPS,
                "dailyEmissionRatePct",SAFE_INITIAL_DAILY_EMISSION_RATE_PCT,
                "dividendBaseFormula",SAFE_INITIAL_DIVIDEND_BASE_FORMULA,"marketState","closed"));
        return ApiResult.ok();
    }

    private CatalogState lockVersion(Long expected){if(expected==null)throw new BizException(400,"EXPECTED_TIERS_VERSION_REQUIRED");CatalogState state=requireStateForUpdate();if(!expected.equals(state.tiersVersion()))throw new BizException(409,"GENESIS_TIERS_VERSION_CONFLICT");return state;}
    private CatalogState requireStateForUpdate(){CatalogState s=mapper.lockState();if(s==null||!Set.of("open","closed").contains(s.marketOpenState())||s.marketOpenStateVersion()==null)throw new BizException(503,"GENESIS_CATALOG_UNAVAILABLE");return s;}
    private List<TierRow> requireTiers(){List<TierRow> rows=mapper.activeTiers();if(rows==null||rows.isEmpty())throw new BizException(503,"GENESIS_TIERS_UNAVAILABLE");validateTiers(rows);return rows;}
    private void validateTiers(List<TierRow> rows){validateTiers(rows,mapper.soldCount());}
    static void validateTiers(List<TierRow> rows, long sold){int next=0;for(TierRow row:rows){if(row.rangeFrom()!=next||row.rangeTo()<=row.rangeFrom()||row.priceUsdt()==null||row.priceUsdt().signum()<=0)throw new BizException(503,"GENESIS_TIERS_INVALID");next=row.rangeTo();}if(next<sold)throw new BizException(503,"GENESIS_TIERS_BELOW_SOLD");}
    private CatalogReadiness readiness(){
        long activeCount=mapper.activeSeriesCount();
        if(activeCount==0){
            if(mapper.soldCount()!=0)return new CatalogReadiness(false,"GENESIS_SERIES_RECOVERY_REQUIRED",true,false,true);
            try{requireTiers();return new CatalogReadiness(false,"GENESIS_SERIES_UNAVAILABLE",true,true,false);}
            catch(BizException ex){return new CatalogReadiness(false,ex.getMessage(),true,false,false);}
        }
        if(activeCount!=1)return new CatalogReadiness(false,"GENESIS_ACTIVE_SERIES_AMBIGUOUS",false,false,false);
        SeriesRow series=mapper.activeSeries();
        if(series==null||series.totalSupply()==null||series.totalSupply()<=0||series.priceUsdt()==null||series.priceUsdt().signum()<=0)
            return new CatalogReadiness(false,"GENESIS_SERIES_INVALID",false,false,false);
        try{
            List<TierRow> tiers=requireTiers();
            if(!Objects.equals(tiers.get(tiers.size()-1).rangeTo(),series.totalSupply()))
                return new CatalogReadiness(false,"GENESIS_TIERS_SUPPLY_MISMATCH",false,false,false);
            long sold=mapper.soldCount();
            if(sold<series.totalSupply()&&tiers.stream().noneMatch(t->sold>=t.rangeFrom()&&sold<t.rangeTo()))
                return new CatalogReadiness(false,"GENESIS_TIER_QUOTE_UNAVAILABLE",false,false,false);
            return new CatalogReadiness(true,"READY",false,false,false);
        }catch(BizException ex){return new CatalogReadiness(false,ex.getMessage(),false,false,false);}
    }
    private void requireReadyForOpen(){CatalogReadiness readiness=readiness();if(!readiness.available())throw new BizException(409,readiness.reason());}
    private void validateBoundary(Integer to, BigDecimal price, int from) {
        if (to == null || to <= from || price == null || price.signum() <= 0
                || price.compareTo(TIER_PRICE_LIMIT) >= 0 || price.stripTrailingZeros().scale() > 0) {
            throw new BizException(422, "GENESIS_TIER_INVALID");
        }
    }
    private int indexOf(List<TierRow> rows,String id){for(int i=0;i<rows.size();i++)if(rows.get(i).tierId().equals(id))return i;throw new BizException(404,"GENESIS_TIER_NOT_FOUND");}
    private List<Map<String,Object>> tierViews(List<TierRow> rows){return rows.stream().map(r->Map.<String,Object>of("id",r.tierId(),"from",r.rangeFrom(),"to",r.rangeTo(),"priceUSDT",r.priceUsdt())).toList();}
    private void requireReason(String reason){if(!StringUtils.hasText(reason)||reason.trim().length()<8||reason.trim().length()>200)throw new BizException(422,"REASON_INVALID");}
    private void requireText(String value,String code,int min,int max){if(!StringUtils.hasText(value)||value.trim().length()<min||value.trim().length()>max)throw new BizException(422,code);}
    private void audit(String action,String id,String actor,String reason,Map<String,Object> detail){Map<String,Object> d=new LinkedHashMap<>(detail);d.put("reason",reason);audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("GENESIS_CATALOG").resourceId(id).actorUsername(AdminActorResolver.resolve(actor)).riskLevel("HIGH").detail(d).build());}
    @SuppressWarnings({"rawtypes","unchecked"}) private ApiResult<Void> once(String action,String key,Object req,Supplier<ApiResult<Void>> supplier){return (ApiResult<Void>)(ApiResult)idempotency.execute("G4_"+action,key,hash(String.valueOf(req)),ApiResult.class,(Supplier)supplier);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private Map<String,Object> unavailablePublicState(CatalogReadiness readiness) {
        CatalogState state = mapper.state();
        return linked("tiers", List.of(), "tiersVersion", state == null ? 0L : state.tiersVersion(),
                "marketOpenState", "closed", "marketOpenStateVersion",
                state == null ? 0L : state.marketOpenStateVersion(), "closedNoticeKey", "maintenance",
                "catalogAvailable", false, "tradeAvailable", false, "tradeBlockedReason", readiness.reason(),
                "seriesSetupRequired", readiness.seriesSetupRequired(),
                "seriesInitializationAvailable", readiness.seriesInitializationAvailable(),
                "seriesRecoveryRequired", readiness.seriesRecoveryRequired());
    }

    private Map<String,Object> linked(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record TierRequest(Integer to,BigDecimal priceUSDT,Long expectedTiersVersion,String reason,String operator){}
    record TierChange(CatalogState state, LockedSeriesRow series, List<TierRow> before, List<TierRow> after,
                      int index, Integer newSupply, boolean amplifies) {
        boolean createsTier() { return index == before.size(); }
        boolean deletesTier() { return after.size() < before.size(); }
        TierRow deletionRecipient() { return after.get(Math.max(0, index - 1)); }
    }
    public record DeleteTierRequest(Long expectedTiersVersion,String reason,String operator){}
    public record MarketStateRequest(String value,String reason,String operator,String noticeKey,
                                     Long expectedMarketOpenStateVersion){}
    public record SeriesBootstrapRequest(String seriesCode,String name,String reason,String operator){}
    private record CatalogReadiness(boolean available,String reason,boolean seriesSetupRequired,
                                    boolean seriesInitializationAvailable,boolean seriesRecoveryRequired){}
}
