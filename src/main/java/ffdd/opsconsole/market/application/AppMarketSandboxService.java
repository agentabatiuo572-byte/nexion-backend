package ffdd.opsconsole.market.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.AppMarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Explicit server-owned Exchange/Genesis sandbox. Never calls production rails. */
@Service
@RequiredArgsConstructor
public class AppMarketSandboxService {
    private final AppMarketSandboxMapper mapper;
    private final Environment environment;
    private final Optional<PlatformConfigFacade> config;

    public ApiResult<Map<String,Object>> exchangeState(Long userId) {
        requireSandboxUser(userId);
        String run = runId();
        AppMarketSandboxMapper.ExchangeWallet wallet = exchangeWallet(run, userId);
        return ApiResult.ok(exchangeView(run, userId, wallet));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> swap(Long userId, String idempotencyKey, AppExchangeService.SwapRequest request) {
        requireSandboxUser(userId);
        String key = key(idempotencyKey, "EXCHANGE_IDEMPOTENCY_KEY_REQUIRED");
        if (request == null || request.fromAmount() == null || request.fromAmount().signum() <= 0) {
            throw new BizException(422, "EXCHANGE_REQUEST_INVALID");
        }
        String from = direction(request.direction());
        BigDecimal amount = amount(request.fromAmount(), "EXCHANGE_AMOUNT_INVALID");
        BigDecimal min = from.equals("USDT") ? number("nexion.exchange.sandbox.min-usdt", "1") : number("nexion.exchange.sandbox.min-nex", "10");
        if (amount.compareTo(min) < 0) throw new BizException(422, "EXCHANGE_AMOUNT_BELOW_MINIMUM");
        String run = runId();
        String hash = hash(from + ":" + amount.stripTrailingZeros() + ":" + Boolean.TRUE.equals(request.queueIfCapped()));
        AppMarketSandboxMapper.ExchangeOrder prior = mapper.exchangeByKey(run, userId, key);
        if (prior != null) return replayExchange(prior, hash, run, userId);
        AppMarketSandboxMapper.ExchangeWallet wallet = exchangeWallet(run, userId);
        prior = mapper.exchangeByKey(run, userId, key);
        if (prior != null) return replayExchange(prior, hash, run, userId);
        BigDecimal price = number("nexion.exchange.sandbox.current-price", "0.125");
        BigDecimal gross = from.equals("USDT") ? amount : money(amount.multiply(price));
        BigDecimal userCap = number("nexion.exchange.sandbox.user-daily-cap-usdt", "50");
        BigDecimal platformCap = number("nexion.exchange.sandbox.platform-daily-cap-usdt", "500");
        mapper.ensureExchangeRunLock(run);
        if (mapper.lockExchangeRun(run) == null) throw new BizException(409, "EXCHANGE_CAP_LOCK_UNAVAILABLE");
        BigDecimal used = moneyOrZero(mapper.userCompletedGrossToday(run, userId));
        BigDecimal platformUsed = moneyOrZero(mapper.platformCompletedGrossToday(run));
        boolean capped = used.add(gross).compareTo(userCap) > 0 || platformUsed.add(gross).compareTo(platformCap) > 0;
        if (capped && !Boolean.TRUE.equals(request.queueIfCapped())) {
            throw new BizException(409, "EXCHANGE_DAILY_CAP_EXCEEDED");
        }
        boolean queued = capped;
        BigDecimal to = from.equals("USDT") ? money(amount.subtract(fee(gross)).divide(price, 6, RoundingMode.DOWN)) : money(amount.multiply(price).subtract(fee(gross)));
        if (to.signum() <= 0) throw new BizException(422, "EXCHANGE_RECEIVE_AMOUNT_INVALID");
        String toAsset = from.equals("USDT") ? "NEX" : "USDT";
        String no = "EX-SBX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        try {
            mapper.insertExchangeOrder(new AppMarketSandboxMapper.ExchangeWrite(run,userId,no,key,hash,from,toAsset,amount,to,price,queued ? "QUEUED" : "COMPLETED"));
        } catch (DuplicateKeyException ex) {
            AppMarketSandboxMapper.ExchangeOrder raced = mapper.exchangeByKey(run,userId,key);
            if (raced != null) return replayExchange(raced, hash, run, userId);
            throw new BizException(409, "EXCHANGE_ORDER_CONFLICT");
        }
        if (!queued) {
            BigDecimal usdt = wallet.usdtAvailable().add(from.equals("USDT") ? amount.negate() : to);
            BigDecimal nex = wallet.nexAvailable().add(from.equals("NEX") ? amount.negate() : to);
            if (usdt.signum() < 0 || nex.signum() < 0 || mapper.updateExchangeWallet(run,userId,usdt,nex,wallet.version()) != 1) {
                throw new BizException(409, "EXCHANGE_WALLET_INSUFFICIENT_OR_CONFLICT");
            }
            if (mapper.insertExchangeLedger(new AppMarketSandboxMapper.ExchangeLedgerWrite(run,userId,no,from,"OUT",amount,from.equals("USDT") ? usdt.add(amount) : nex.add(amount),"sandbox exchange debit")) != 1
                    || mapper.insertExchangeLedger(new AppMarketSandboxMapper.ExchangeLedgerWrite(run,userId,no,toAsset,"IN",to,toAsset.equals("USDT") ? usdt : nex,"sandbox exchange credit")) != 1) throw new BizException(409,"EXCHANGE_SANDBOX_LEDGER_CONFLICT");
        }
        return ApiResult.ok(exchangeView(run,userId,mapper.exchangeWallet(run,userId),no));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> cancelExchange(Long userId,String exchangeNo,String idempotencyKey) {
        requireSandboxUser(userId); String run=runId(); String key=key(idempotencyKey,"EXCHANGE_IDEMPOTENCY_KEY_REQUIRED");
        String requestHash=hash("cancel:"+exchangeNo);
        AppMarketSandboxMapper.ExchangeOperation prior=mapper.exchangeOperation(run,userId,key);
        if (prior!=null) { if(!requestHash.equals(prior.requestHash())||!exchangeNo.equals(prior.exchangeNo())) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); return ApiResult.ok(exchangeView(run,userId,exchangeWallet(run,userId),exchangeNo)); }
        AppMarketSandboxMapper.ExchangeOrder row=mapper.exchangeByNo(run,userId,exchangeNo);
        if (row==null || !"QUEUED".equals(row.status())) throw new BizException(409,"EXCHANGE_NOT_CANCELLABLE");
        if (mapper.cancelExchange(run,userId,exchangeNo)!=1) {
            AppMarketSandboxMapper.ExchangeOperation raced=mapper.exchangeOperation(run,userId,key);
            if(raced!=null&&requestHash.equals(raced.requestHash())&&exchangeNo.equals(raced.exchangeNo()))
                return ApiResult.ok(exchangeView(run,userId,exchangeWallet(run,userId),exchangeNo));
            throw new BizException(409,"EXCHANGE_NOT_CANCELLABLE");
        }
        try { mapper.insertExchangeOperation(new AppMarketSandboxMapper.ExchangeOperationWrite(run,userId,key,requestHash,exchangeNo)); }
        catch (DuplicateKeyException ex) { AppMarketSandboxMapper.ExchangeOperation raced=mapper.exchangeOperation(run,userId,key); if(raced==null||!requestHash.equals(raced.requestHash())) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); }
        return ApiResult.ok(exchangeView(run,userId,mapper.exchangeWallet(run,userId),exchangeNo));
    }

    public ApiResult<Map<String,Object>> genesisState() {
        String run=runId();
        return ApiResult.ok(genesisStateView(run));
    }

    public ApiResult<Map<String,Object>> genesisAccount(Long userId) {
        requireSandboxUser(userId); String run=runId(); AppMarketSandboxMapper.GenesisWallet wallet=genesisWallet(run,userId);
        return ApiResult.ok(genesisAccountView(run,userId,wallet));
    }

    public ApiResult<Map<String,Object>> genesisEligibility(Long userId) {
        requireSandboxUser(userId);
        String run=runId();
        return ApiResult.ok(genesisEligibilityView(run,userId,sandboxHoldings(run,userId).size()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> genesisRedeemInvite(Long userId,String idempotencyKey,String code) {
        requireSandboxUser(userId);
        String key=key(idempotencyKey,"GENESIS_IDEMPOTENCY_KEY_REQUIRED");
        String normalized=StringUtils.hasText(code)?code.trim().toUpperCase(Locale.ROOT):"";
        if(!normalized.matches("[A-Z0-9_-]{4,32}")) throw new BizException(422,"GENESIS_INVITE_CODE_INVALID");
        String run=runId();
        AppMarketSandboxMapper.GenesisOrder prior=mapper.genesisOrderByKey(run,userId,key);
        if(prior!=null){
            if(!"INVITE".equals(prior.orderType())||!normalized.equals(prior.holdingNo()))
                throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT");
            return ApiResult.ok(linked("redeemed",true,"code",normalized,"orderNo",prior.orderNo(),
                    "serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run));
        }
        String order="G4I-SBX-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        try {
            if(mapper.insertGenesisOrder(new AppMarketSandboxMapper.GenesisOrderWrite(run,order,key,userId,normalized,
                    "INVITE",BigDecimal.ZERO,BigDecimal.ZERO,null))!=1) throw new BizException(409,"GENESIS_INVITE_CONFLICT");
        } catch (DuplicateKeyException ex) {
            AppMarketSandboxMapper.GenesisOrder raced=mapper.genesisOrderByKey(run,userId,key);
            if(raced==null||!"INVITE".equals(raced.orderType())||!normalized.equals(raced.holdingNo()))
                throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT");
            order=raced.orderNo();
        }
        return ApiResult.ok(linked("redeemed",true,"code",normalized,"orderNo",order,
                "serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> genesisPurchase(Long userId,String idempotencyKey,AppGenesisService.PurchaseRequest request) {
        requireSandboxUser(userId); String key=key(idempotencyKey,"GENESIS_IDEMPOTENCY_KEY_REQUIRED"); int quantity=request==null||request.quantity()==null?0:request.quantity();
        if(quantity<1||quantity>20) throw new BizException(422,"GENESIS_QUANTITY_INVALID"); String run=runId(); String hash=hash("purchase:"+quantity);
        AppMarketSandboxMapper.GenesisOrder prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null) return replayGenesis(prior,hash,run,userId);
        AppMarketSandboxMapper.GenesisWallet wallet=genesisWallet(run,userId); prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null) return replayGenesis(prior,hash,run,userId);
        long owned = sandboxHoldings(run,userId).size();
        if (owned + quantity > 20) throw new BizException(409,"GENESIS_MAX_PER_USER_EXCEEDED");
        BigDecimal price=positiveMoneyNumber("nexion.genesis.sandbox.price-usdt","10"); BigDecimal amount=money(price.multiply(BigDecimal.valueOf(quantity)));
        if(wallet.usdtAvailable().compareTo(amount)<0) throw new BizException(409,"GENESIS_WALLET_INSUFFICIENT");
        String order="G4-SBX-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        if(mapper.updateGenesisWallet(run,userId,wallet.usdtAvailable().subtract(amount),wallet.version())!=1) throw new BizException(409,"GENESIS_WALLET_CONFLICT");
        if(mapper.insertGenesisOrder(new AppMarketSandboxMapper.GenesisOrderWrite(run,order,key,userId,null,"PRIMARY",amount,price,null))!=1) throw new BizException(409,"GENESIS_ORDER_CONFLICT");
        for(int i=1;i<=quantity;i++) if (mapper.insertGenesisHolding(new AppMarketSandboxMapper.HoldingWrite(run,order+"-"+i,order,userId,price)) != 1) throw new BizException(409,"GENESIS_SANDBOX_HOLDING_CONFLICT");
        if (mapper.insertGenesisLedger(new AppMarketSandboxMapper.GenesisLedgerWrite(run,userId,order,"OUT",amount,wallet.usdtAvailable().subtract(amount),"sandbox genesis purchase")) != 1) throw new BizException(409,"GENESIS_SANDBOX_LEDGER_CONFLICT");
        return ApiResult.ok(genesisAccountView(run,userId,mapper.genesisWallet(run,userId),order));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> genesisList(Long userId,String holdingNo,String idempotencyKey,AppGenesisService.ListingRequest request) {
        requireSandboxUser(userId); String key=key(idempotencyKey,"GENESIS_IDEMPOTENCY_KEY_REQUIRED"); BigDecimal price=request==null?null:request.askPriceUsdt();
        if(price==null||price.signum()<=0) throw new BizException(422,"GENESIS_LISTING_PRICE_INVALID");
        price=amount(price,"GENESIS_LISTING_PRICE_INVALID"); String run=runId();
        AppMarketSandboxMapper.GenesisOrder prior=mapper.genesisOrderByKey(run,userId,key);
        if(prior!=null) { if(!"LIST".equals(prior.orderType())||!holdingNo.equals(prior.holdingNo())||!money(price).equals(prior.priceUsdt())) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),prior.orderNo())); }
        AppMarketSandboxMapper.HoldingView h=mapper.holding(run,holdingNo);
        if(h==null||!userId.equals(h.userId())||!"ACTIVE".equals(h.status())||mapper.listHolding(run,holdingNo,userId,money(price))!=1) { prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null&&"LIST".equals(prior.orderType())&&holdingNo.equals(prior.holdingNo())) return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),prior.orderNo())); throw new BizException(409,"GENESIS_HOLDING_NOT_LISTABLE"); }
        String order="G4L-SBX-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        if(mapper.insertGenesisOrder(new AppMarketSandboxMapper.GenesisOrderWrite(run,order,key,userId,holdingNo,"LIST",BigDecimal.ZERO,money(price),null))!=1) throw new BizException(409,"GENESIS_ORDER_CONFLICT");
        return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),order));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> genesisCancel(Long userId,String holdingNo,String idempotencyKey) {
        requireSandboxUser(userId); String key=key(idempotencyKey,"GENESIS_IDEMPOTENCY_KEY_REQUIRED"); String run=runId();
        AppMarketSandboxMapper.GenesisOrder prior=mapper.genesisOrderByKey(run,userId,key);
        if(prior!=null) { if(!"CANCEL".equals(prior.orderType())||!holdingNo.equals(prior.holdingNo())) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),prior.orderNo())); }
        if(mapper.cancelHolding(run,holdingNo,userId)!=1) { prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null&&"CANCEL".equals(prior.orderType())&&holdingNo.equals(prior.holdingNo())) return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),prior.orderNo())); throw new BizException(409,"GENESIS_LISTING_NOT_ACTIVE"); }
        String order="G4C-SBX-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        if(mapper.insertGenesisOrder(new AppMarketSandboxMapper.GenesisOrderWrite(run,order,key,userId,holdingNo,"CANCEL",BigDecimal.ZERO,BigDecimal.ZERO,null))!=1) throw new BizException(409,"GENESIS_ORDER_CONFLICT");
        return ApiResult.ok(genesisAccountView(run,userId,genesisWallet(run,userId),order));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String,Object>> genesisBuy(Long userId,String holdingNo,String idempotencyKey) {
        requireSandboxUser(userId); String key=key(idempotencyKey,"GENESIS_IDEMPOTENCY_KEY_REQUIRED"); String run=runId();
        AppMarketSandboxMapper.GenesisOrder prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null) return replayGenesis(prior,hash("buy:"+holdingNo),run,userId);
        AppMarketSandboxMapper.HoldingView h=mapper.holding(run,holdingNo); if(h==null||!"LISTED".equals(h.status())||h.listingPriceUsdt()==null) { prior=mapper.genesisOrderByKey(run,userId,key); if(prior!=null) return replayGenesis(prior,hash("buy:"+holdingNo),run,userId); throw new BizException(409,"GENESIS_LISTING_NOT_ACTIVE"); } if(userId.equals(h.userId())) throw new BizException(409,"GENESIS_SELF_TRADE_FORBIDDEN");
        requireSandboxUser(h.userId());
        AppMarketSandboxMapper.GenesisWallet buyer, seller;
        if (userId < h.userId()) { buyer=genesisWallet(run,userId); seller=genesisWallet(run,h.userId()); }
        else { seller=genesisWallet(run,h.userId()); buyer=genesisWallet(run,userId); }
        BigDecimal price=money(h.listingPriceUsdt()); if(buyer.usdtAvailable().compareTo(price)<0) throw new BizException(409,"GENESIS_WALLET_INSUFFICIENT");
        String order="G4S-SBX-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        if(mapper.updateGenesisWallet(run,userId,buyer.usdtAvailable().subtract(price),buyer.version())!=1 || mapper.updateGenesisWallet(run,h.userId(),seller.usdtAvailable().add(price),seller.version())!=1) throw new BizException(409,"GENESIS_WALLET_CONFLICT");
        if(mapper.insertGenesisOrder(new AppMarketSandboxMapper.GenesisOrderWrite(run,order,key,userId,holdingNo,"SECONDARY",price,price,h.userId()))!=1 || mapper.transferHolding(run,holdingNo,h.userId(),userId,order,price)!=1) throw new BizException(409,"GENESIS_ORDER_CONFLICT");
        if (mapper.insertGenesisLedger(new AppMarketSandboxMapper.GenesisLedgerWrite(run,userId,order,"OUT",price,buyer.usdtAvailable().subtract(price),"sandbox secondary buy")) != 1
                || mapper.insertGenesisLedger(new AppMarketSandboxMapper.GenesisLedgerWrite(run,h.userId(),order,"IN",price,seller.usdtAvailable().add(price),"sandbox secondary sale")) != 1) throw new BizException(409,"GENESIS_SANDBOX_LEDGER_CONFLICT");
        return ApiResult.ok(genesisAccountView(run,userId,mapper.genesisWallet(run,userId),order));
    }

    private ApiResult<Map<String,Object>> replayExchange(AppMarketSandboxMapper.ExchangeOrder row,String expected,String run,Long user) { if(!row.requestHash().equals(expected)) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); return ApiResult.ok(exchangeView(run,user,mapper.exchangeWallet(run,user),row.exchangeNo())); }
    private ApiResult<Map<String,Object>> replayGenesis(AppMarketSandboxMapper.GenesisOrder row,String expected,String run,Long user) { String basis=switch(row.orderType()){case "PRIMARY"->"purchase:"+row.amountUsdt().divide(row.priceUsdt(),0,RoundingMode.DOWN);case "SECONDARY"->"buy:"+row.holdingNo();case "LIST"->"list:"+row.holdingNo()+":"+row.priceUsdt();case "CANCEL"->"cancel:"+row.holdingNo();default->row.orderType()+":"+row.holdingNo();}; if(!hash(basis).equals(expected)&&!(("LIST".equals(row.orderType())||"CANCEL".equals(row.orderType()))&&expected.equals(hash("noop:"+row.holdingNo())))) throw new BizException(409,"IDEMPOTENCY_KEY_REUSE_CONFLICT"); return ApiResult.ok(genesisAccountView(run,user,genesisWallet(run,user),row.orderNo())); }
    private AppMarketSandboxMapper.ExchangeWallet exchangeWallet(String run,Long user){ mapper.ensureExchangeWallet(run,user,nonNegativeMoneyNumber("nexion.exchange.sandbox.initial-usdt","1000"),nonNegativeMoneyNumber("nexion.exchange.sandbox.initial-nex","0")); AppMarketSandboxMapper.ExchangeWallet w=mapper.exchangeWallet(run,user); if(w==null) throw new BizException(409,"EXCHANGE_SANDBOX_WALLET_UNAVAILABLE"); return w; }
    private AppMarketSandboxMapper.GenesisWallet genesisWallet(String run,Long user){ mapper.ensureGenesisWallet(run,user,nonNegativeMoneyNumber("nexion.genesis.sandbox.initial-usdt","1000")); AppMarketSandboxMapper.GenesisWallet w=mapper.genesisWallet(run,user); if(w==null) throw new BizException(409,"GENESIS_SANDBOX_WALLET_UNAVAILABLE"); return w; }
    private Map<String,Object> exchangeView(String run,Long user,AppMarketSandboxMapper.ExchangeWallet w){ return exchangeView(run,user,w,null); }
    private Map<String,Object> exchangeView(String run,Long user,AppMarketSandboxMapper.ExchangeWallet w,String no){ Map<String,Object> m=linked("wallet",linked("usdtAvailable",money(w.usdtAvailable()),"nexAvailable",money(w.nexAvailable())),"orders",mapper.exchangeOrders(run,user),"ledger",mapper.exchangeLedger(run,user),"serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run); if(no!=null)m.put("exchangeNo",no); return m; }
    private Map<String,Object> genesisStateView(String run){ BigDecimal price=positiveMoneyNumber("nexion.genesis.sandbox.price-usdt","10"); return linked("series",linked("seriesCode","GENESIS-SANDBOX","name","Genesis Sandbox","totalSupply",1000,"soldSupply",sandboxHoldingCount(run),"priceUsdt",price),"market",linked("enabled",true,"internalP2POnly",true),"sale",linked("available",true,"open",true,"maxPerUser",20,"unitPriceUsdt",price),"tradeAvailable",true,"listings",mapper.listings(run),"serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run); }
    private Map<String,Object> genesisAccountView(String run,Long user,AppMarketSandboxMapper.GenesisWallet w){ return genesisAccountView(run,user,w,null); }
    private Map<String,Object> genesisAccountView(String run,Long user,AppMarketSandboxMapper.GenesisWallet w,String order){
        List<AppMarketSandboxMapper.HoldingView> holdings = sandboxHoldings(run, user);
        long owned=holdings.size();
        Map<String,Object> m=linked("series",linked("seriesCode","GENESIS-SANDBOX","name","Genesis Sandbox","totalSupply",1000,
                "soldSupply",sandboxHoldingCount(run),"remainingSupply",Math.max(0,1000-sandboxHoldingCount(run)),"priceUsdt",positiveMoneyNumber("nexion.genesis.sandbox.price-usdt","10"),"royaltyPct",0,"dailyEmissionRatePct",0),
                "sale",linked("serverCanonical",true,"available",true,"eligibilityEnabled",true,"maxPerUser",20,"minAccountAgeDays",0,"presaleEnabled",false,"showCountdown",false,"unitPriceUsdt",positiveMoneyNumber("nexion.genesis.sandbox.price-usdt","10"),"open",true),
                "marketEnabled",true,"emissionOpen",false,"holdings",holdings,"emissions",List.of(),"walletBalanceUsdt",money(w.usdtAvailable()),"ledger",mapper.genesisLedger(run,user),
                "eligibility",genesisEligibilityView(run,user,owned),"serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run);
        if(order!=null)m.put("orderNo",order); return m;
    }
    private Map<String,Object> genesisEligibilityView(String run,Long user,long owned){
        long remaining=Math.max(0,20L-owned);
        boolean eligible=remaining>0;
        List<String> reasons=new java.util.ArrayList<>();
        if(remaining==0) reasons.add("MAX_PER_USER_REACHED");
        reasons.add(owned>0?"HOLDINGS_CONFIRMED":"NO_ACTIVE_HOLDINGS");
        Instant now=Instant.now();
        Map<String,Object> result=linked("eligible",eligible,"reasons",reasons,"qualificationReasonCodes",new java.util.ArrayList<>(reasons),"ownedCount",owned,"maxPerUser",20,"remainingCap",remaining,
                "minAccountAgeDays",0,"accountAgeDays",1,"hasGenesisInvite",false,"halted",false,"status","CONFIG_UNAVAILABLE",
                "reservedAllocation",null,"reservedAllocationUnit","NEX","priorityRank",null,"priorityTier","NONE",
                "policyVersion",null,"effectiveAt",null,"asOf",now.toString(),"serverTime",now.toString(),
                "provenance",linked("source","nx_genesis_sandbox_holding+nx_config_item","environment","SANDBOX","runId",run),
                "serverCanonical",true,"source","mock","sourceEnvironment","SANDBOX","runId",run);
        try {
            BigDecimal perHolding=requiredHolderDecimal("allocationNexPerHolding");
            int top1=requiredHolderInteger("priorityTop1Percent");
            int top3=requiredHolderInteger("priorityTop3Percent");
            int top5=requiredHolderInteger("priorityTop5Percent");
            String version=requiredHolderText("policyVersion");
            Instant effectiveAt=Instant.parse(requiredHolderText("effectiveAt"));
            if(perHolding.signum()<=0||top1<=0||top1>top3||top3>top5||top5>100) throw new IllegalArgumentException("holder policy bounds");
            @SuppressWarnings("unchecked") List<String> qualificationCodes=(List<String>)result.get("qualificationReasonCodes");
            qualificationCodes.add(effectiveAt.isAfter(now)?"POLICY_NOT_EFFECTIVE":"POLICY_CONFIRMED");
            Integer rank=AppGenesisSandboxFixtureService.hasFixture(run) ? AppGenesisSandboxFixtureService.priorityRank(run,user) : mapper.currentPriorityRank(run,user,"GENESIS-SANDBOX");
            long holderCount=Math.max(0L,AppGenesisSandboxFixtureService.hasFixture(run) ? AppGenesisSandboxFixtureService.activeHolderCount(run) : mapper.activeHolderCount(run,"GENESIS-SANDBOX"));
            result.put("reservedAllocation",nexQuantity(perHolding.multiply(BigDecimal.valueOf(owned))));
            result.put("priorityRank",rank==null||rank<1?null:rank);
            result.put("priorityTier",priorityTier(rank,holderCount,top1,top3,top5));
            result.put("policyVersion",version);
            result.put("effectiveAt",effectiveAt.toString());
            result.put("status",effectiveAt.isAfter(now)?"NOT_EFFECTIVE":owned>0?"READY":"NOT_ELIGIBLE");
        } catch (RuntimeException ignored) {
            // Missing/withdrawn/malformed G4 holder policy is explicit; never estimate locally.
        }
        return result;
    }
    private List<AppMarketSandboxMapper.HoldingView> sandboxHoldings(String run, Long user) { return AppGenesisSandboxFixtureService.hasFixture(run) ? AppGenesisSandboxFixtureService.holdings(run,user) : mapper.holdings(run,user); }
    private long sandboxHoldingCount(String run) { return AppGenesisSandboxFixtureService.hasFixture(run) ? AppGenesisSandboxFixtureService.totalHoldings(run) : mapper.holdingCount(run); }
    private String priorityTier(Integer rank,long holderCount,int top1,int top3,int top5){
        if(rank==null||rank<1||holderCount<=0)return "NONE";
        long one=Math.max(1L,(holderCount*top1+99L)/100L), three=Math.max(one,(holderCount*top3+99L)/100L), five=Math.max(three,(holderCount*top5+99L)/100L);
        if(rank<=one)return "TOP_1"; if(rank<=three)return "TOP_3"; if(rank<=five)return "TOP_5"; return "STANDARD";
    }
    private BigDecimal requiredHolderDecimal(String key){return config.flatMap(c->c.activeValue("market.genesis.ops.holder."+key)).map(raw->new BigDecimal(raw.trim())).orElseThrow();}
    private int requiredHolderInteger(String key){return config.flatMap(c->c.activeValue("market.genesis.ops.holder."+key)).map(raw->Integer.parseInt(raw.trim())).orElseThrow();}
    private String requiredHolderText(String key){return config.flatMap(c->c.activeValue("market.genesis.ops.holder."+key)).filter(StringUtils::hasText).map(String::trim).orElseThrow();}
    private BigDecimal fee(BigDecimal gross){ BigDecimal pct=nonNegativeNumber("nexion.exchange.sandbox.fee-pct","0"); if(pct.compareTo(BigDecimal.valueOf(100))>0)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return money(gross.multiply(pct).divide(BigDecimal.valueOf(100),12,RoundingMode.HALF_UP)); }
    private String direction(String v){ String s=StringUtils.hasText(v)?v.trim().toUpperCase(Locale.ROOT).replace("-","_"):""; return switch(s){case "USDT_TO_NEX","USDT2NEX"->"USDT";case "NEX_TO_USDT","NEX2USDT"->"NEX";default->throw new BizException(422,"EXCHANGE_DIRECTION_INVALID");}; }
    private String key(String v,String error){ if(!StringUtils.hasText(v)||v.trim().length()>200)throw new BizException(422,error); return v.trim(); }
    private BigDecimal number(String key,String fallback){ BigDecimal value=parseNumber(key,fallback); if(value.signum()<=0)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return value; }
    private BigDecimal nonNegativeNumber(String key,String fallback){ BigDecimal value=parseNumber(key,fallback); if(value.signum()<0)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return value; }
    private BigDecimal positiveMoneyNumber(String key,String fallback){ BigDecimal value=number(key,fallback); if(Math.max(0,value.scale())>6||value.precision()>36)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return value.setScale(6); }
    private BigDecimal nonNegativeMoneyNumber(String key,String fallback){ BigDecimal value=nonNegativeNumber(key,fallback); if(Math.max(0,value.scale())>6||value.precision()>36)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return value.setScale(6); }
    private BigDecimal parseNumber(String key,String fallback){ try{BigDecimal value=new BigDecimal(environment.getProperty(key,fallback)); if(value.precision()>36||Math.max(0,value.scale())>12)throw new BizException(503,"SANDBOX_CONFIG_INVALID"); return value;}catch(BizException ex){throw ex;}catch(RuntimeException ex){throw new BizException(503,"SANDBOX_CONFIG_INVALID");} }
    private BigDecimal amount(BigDecimal value,String error){if(value==null||value.signum()<=0||value.precision()>36||Math.max(0,value.scale())>6)throw new BizException(422,error);return value.setScale(6,RoundingMode.UNNECESSARY);}
    private BigDecimal money(BigDecimal v){if(v==null)throw new BizException(503,"SANDBOX_CONFIG_INVALID");BigDecimal value=v.setScale(6,RoundingMode.HALF_UP);if(value.precision()>36)throw new BizException(422,"SANDBOX_AMOUNT_OVERFLOW");return value;}
    private BigDecimal nexQuantity(BigDecimal v){if(v==null)throw new BizException(503,"SANDBOX_CONFIG_INVALID");BigDecimal value=v.setScale(2,RoundingMode.HALF_UP);if(value.precision()>36)throw new BizException(422,"SANDBOX_AMOUNT_OVERFLOW");return value;}
    private BigDecimal moneyOrZero(BigDecimal value){return money(value==null?BigDecimal.ZERO:value);}
    private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String runId(){String run=environment.getProperty("NEXION_ACCEPTANCE_RUN_ID","").trim();if(!run.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}"))throw new BizException(503,"MARKET_SANDBOX_RUN_ID_REQUIRED");return run;}
    private void requireSandboxUser(Long user){if(user==null||user<=0)throw new BizException(401,"USER_AUTH_REQUIRED");if(!FundsSandboxProfileGuard.isStrictIsolatedProfile(environment.getActiveProfiles()))throw new BizException(503,"MARKET_SANDBOX_PROFILE_REQUIRED");if(!Integer.valueOf(1).equals(mapper.userSandbox(user)))throw new BizException(403,"MARKET_SANDBOX_USER_REQUIRED");runId();}
    private Map<String,Object> linked(Object...v){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
}
