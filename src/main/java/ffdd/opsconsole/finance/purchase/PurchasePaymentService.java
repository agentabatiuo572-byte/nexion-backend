package ffdd.opsconsole.finance.purchase;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.commerce.application.AppOrderCommandService;
import ffdd.opsconsole.device.application.AppTradeinService;
import ffdd.opsconsole.device.dto.*;
import ffdd.opsconsole.growth.application.AppTrialLifecycleService;
import ffdd.opsconsole.finance.application.VietnamPaymentPolicy;
import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.finance.mapper.PurchasePaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static ffdd.opsconsole.finance.purchase.PurchasePaymentPolicyService.money;

/** Durable direct purchase intents. No wallet principal transaction belongs to this rail. */
@Service @RequiredArgsConstructor
public class PurchasePaymentService {
    private final PurchasePaymentMapper db;
    private final PurchasePaymentPolicyService policy;
    private final AppOrderCommandService orders;
    private final AppTradeinService tradeins;
    private final AppTrialLifecycleService trials;
    private final HdPayProperties hd;
    private final HdPayGateway hdGateway;
    private final CregisProperties cr;
    private final CregisGatewayRouter crGateway;
    private final BscDepositProof chain;
    private final VietnamPaymentMapper fx;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper jsonMapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService commands;
    private static final Set<String> KINDS=Set.of("ORDER_PAY","TRIAL_CONVERT","TRADE_IN","CAPACITY_REPLACE","CAPACITY_KEEP");
    private static final String SELECT="SELECT * FROM nx_purchase_payment_intent WHERE intent_no=?";

    private <T> T tx(java.util.function.Supplier<T> work){
        var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx.execute(status->work.get());
    }
    private void user(long owner,boolean lock){
        var row=db.one("SELECT id,status,CAST(sandbox AS UNSIGNED) sandbox FROM nx_user WHERE id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),owner);
        if(row==null||!"ACTIVE".equals(row.get("status"))||number(row.get("sandbox"))!=0)
            throw new BizException(403,"PURCHASE_PAYMENT_USER_UNAVAILABLE");
    }
    private static boolean cregis(Object method){return Set.of("CREGIS_USDT_BSC","CREGIS_USDT_ERC20").contains(method);}
    private static CregisNetwork network(Object method){
        return switch(text(method)){
            case "CREGIS_USDT_BSC" -> CregisNetwork.BEP20;
            case "CREGIS_USDT_ERC20" -> CregisNetwork.ERC20;
            default -> throw new BizException(422,"PURCHASE_PAYMENT_METHOD_INVALID");
        };
    }
    private static String method(CregisNetwork network){return network==CregisNetwork.BEP20?"CREGIS_USDT_BSC":"CREGIS_USDT_ERC20";}
    private void ready(String method){
        if("HDPAY_BANK".equals(method)){
            if(!hd.ready())throw new BizException(503,"HDPAY_CONFIGURATION_INCOMPLETE");
            HdPayServerIp.requirePublicIpv4(hd.getServerIp());
        }else if(cregis(method)){
            var network=network(method);
            if(cr.getMode()!=CregisProperties.Mode.PROVIDER||!cr.isDepositEnabled()||!cr.isDepositCreditEnabled()||cr.getProjectId()<=0||cr.confirmations(network)<15||!cr.networkConfigured(network)||!cr.depositNetworkEnabled(network))
                throw new BizException(503,"CREGIS_DIRECT_PAYMENT_UNAVAILABLE");
            if(crGateway.provider().projectCoins().stream().noneMatch(coin->coin.addressEnabled()
                    &&network.chainId().equals(coin.chainId())&&network.tokenId().equalsIgnoreCase(coin.tokenId())))
                throw new BizException(503,"CREGIS_DIRECT_PAYMENT_ASSET_UNAVAILABLE");
            cregisGate(network,true,false);
        }else throw new BizException(422,"PURCHASE_PAYMENT_METHOD_INVALID");
    }
    private void cregisGate(CregisNetwork network,boolean assignment,boolean lock){
        if(!cr.isDepositCreditEnabled()||!cr.depositNetworkEnabled(network))throw new BizException(409,"CREGIS_DIRECT_PAYMENT_PAUSED");
        var gate=db.one("SELECT state,CAST(assign_enabled AS UNSIGNED) assign_enabled,CAST(credit_enabled AS UNSIGNED) credit_enabled FROM nx_cregis_provision_gate WHERE id=1"+(lock?" FOR UPDATE":""));
        if(gate==null||!"IDLE".equals(gate.get("state"))||number(gate.get("credit_enabled"))!=1||assignment&&number(gate.get("assign_enabled"))!=1)
            throw new BizException(409,"CREGIS_DIRECT_PAYMENT_PAUSED");
    }
    private Map<String,Object> dedicatedAddress(CregisNetwork network){
        var candidate=db.one("SELECT id,address FROM nx_cregis_deposit_address WHERE project_id=? AND chain_id=? AND state='UNASSIGNED' AND user_id IS NULL AND purpose='WALLET_TOPUP' ORDER BY id LIMIT 1",cr.getProjectId(),network.chainId());
        if(candidate==null)throw new BizException(503,"CREGIS_DIRECT_ADDRESS_POOL_UNAVAILABLE");
        String address=text(candidate.get("address"));
        if(!crGateway.provider().addressBelongs(network.chainId(),address)
                ||!crGateway.provider().zeroAddressBalance(network.currency(),address)||!chain.zeroUsdtBalance(network,address))
            throw new BizException(409,"CREGIS_DIRECT_ADDRESS_REQUIRES_REVIEW");
        var head=chain.head(network);if(!head.hash().equalsIgnoreCase(chain.blockHash(network,head.number())))throw new BizException(503,"CREGIS_DIRECT_ALLOCATION_ANCHOR_INVALID");
        candidate.put("allocationBlock",head.number());candidate.put("allocationHash",head.hash());candidate.put("proofExpiresAt",Instant.now().plusSeconds(5));
        return candidate;
    }
    public Map<String,Object> quote(long owner,Map<String,Object> input){
        String kind=text(input.get("kind")),method=text(input.get("paymentMethod"));
        if(!KINDS.contains(kind))throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        Map<String,Object> payload=map(input.get("payload"));
        // Credential material is rejected before serialisation, hashing, logging or persistence.
        if(payload.containsKey("paymentPassword")||payload.containsKey("credentialVersion")||payload.containsKey("attemptNo"))
            throw new BizException(422,"EXTERNAL_PAYMENT_CREDENTIAL_FORBIDDEN");
        ready(method);
        return tx(()->{
            user(owner,true);Instant pricingAt=Instant.now();var pricing=pricing(owner,kind,payload,pricingAt);
            if(!db.rows("SELECT intent_no FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT','FUNDS_CONFIRMED','MANUAL_REVIEW') AND payload_json=CAST(? AS JSON)",owner,kind,json(payload)).isEmpty())
                throw new BizException(409,"PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
            BigDecimal subtotal=money(pricing.get("subtotalUsdt"));
            if(subtotal.signum()<=0)throw new BizException(409,"ZERO_PAYMENT_REQUIRES_WALLET");
            var current=policy.current();var channel=maps(current.get("channels")).stream()
                    .filter(c->method.equals(c.get("paymentMethod"))).findFirst().orElseThrow();
            if(!Boolean.TRUE.equals(channel.get("configured")))throw new BizException(503,"PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");
            BigDecimal rate=money(channel.get("channelFeeRatePct")),fixed=money(channel.get("channelFeeFixed")),network=money(channel.get("networkConfirmFeeUsdt"));
            BigDecimal fee=subtotal.multiply(rate).divide(new BigDecimal("100")).add(fixed).setScale(2,RoundingMode.HALF_UP);
            BigDecimal total=subtotal.add(fee).add(network).setScale(2,RoundingMode.HALF_UP);
            String id=id("PPQ-");Instant expires=Instant.now().plusSeconds(900);
            var quote=values("paymentQuoteId",id,"kind",kind,"paymentMethod",method,"network","HDPAY_BANK".equals(method)?"VND_BANK":network(method)==CregisNetwork.BEP20?"BSC":"ERC20",
                    "subtotalUsdt",subtotal.toPlainString(),"businessAmountUsdt",pricing.get("businessAmountUsdt"),
                    "roundingAdjustmentUsdt",subtotal.subtract(new BigDecimal(text(pricing.get("businessAmountUsdt")))).setScale(6,RoundingMode.UNNECESSARY).toPlainString(),"channelFeeRatePct",rate.toPlainString(),"channelFeeFixed",fixed.toPlainString(),
                    "channelFeeUsdt",fee.toPlainString(),"networkConfirmFeeUsdt",network.toPlainString(),"totalUsdt",total.toPlainString(),
                    "feePolicyVersion",current.get("version"),"paymentFeesConfigured",true,"pricingAsOf",pricingAt.toString(),"expiresAt",expires.toString(),"currency","HDPAY_BANK".equals(method)?"VND":"USDT");
            if("HDPAY_BANK".equals(method)){
                var config=fx.findFxQuoteConfig();if(config==null)throw new BizException(503,"FX_QUOTE_UNAVAILABLE");
                BigDecimal quoted=VietnamPaymentPolicy.quoteRate(new BigDecimal(text(config.get("baseRateVndPerUsdt"))),new BigDecimal(text(config.get("buySpreadPct"))));
                if(quoted.signum()<=0)throw new BizException(503,"FX_QUOTE_UNAVAILABLE");
                quote.put("fxRateVndPerUsdt",quoted.toPlainString());quote.put("fxVersion",config.get("version"));
                quote.put("amountVnd",total.multiply(quoted).setScale(0,RoundingMode.HALF_UP).toPlainString());
            }
            db.write("INSERT INTO nx_purchase_payment_quote(quote_id,user_id,kind,payment_method,payload_json,quote_json,pricing_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                    id,owner,kind,method,json(payload),json(quote),hash(pricing),timestamp(expires));
            return quote;
        });
    }
    private Map<String,Object> pricing(long owner,String kind,Map<String,Object> payload,Instant pricingAt){
        switch(kind){
            case "ORDER_PAY":{
                var order=db.one("SELECT * FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0 FOR UPDATE",text(payload.get("orderNo")),owner);
                if(order==null)throw new BizException(404,"ORDER_NOT_FOUND");
                if(!"PENDING".equals(order.get("payment_status"))||!"PENDING_PAYMENT".equals(order.get("order_status")))throw new BizException(409,"ORDER_NOT_PAYABLE");
                return values("businessAmountUsdt",new BigDecimal(text(order.get("amount_usdt"))).toPlainString(),"subtotalUsdt",money(order.get("amount_usdt")).toPlainString(),"orderNo",order.get("order_no"),
                        "subtotal",text(order.get("subtotal_usdt")),"discount",text(order.get("discount_usdt")),
                        "lines",db.rows("SELECT product_id,product_no,quantity,unit_price_usdt FROM nx_order_item WHERE order_no=? AND is_deleted=0 ORDER BY id",order.get("order_no")));
            }
            case "TRADE_IN":{
                var q=require(tradeins.quote(owner,new AppTradeinQuoteRequest(optionalLong(payload.get("sourceDeviceId")),optionalLong(payload.get("targetProductId")),text(payload.get("targetProductNo")))));
                if(payload.get("expectedSourceVersion")==null||number(payload.get("expectedSourceVersion"))!=q.sourceRowVersion())
                    throw new BizException(409,"TRADEIN_SOURCE_VERSION_CONFLICT");
                return values("businessAmountUsdt",q.payableUsdt().toPlainString(),"subtotalUsdt",money(q.payableUsdt()).toPlainString(),"sourceVersion",q.sourceRowVersion(),"sourceDeviceId",q.sourceDeviceId(),
                        "targetProductId",q.targetProductId(),"targetPrice",q.targetPriceUsdt().toPlainString(),"discount",q.discountUsdt().toPlainString());
            }
            case "CAPACITY_REPLACE":case "CAPACITY_KEEP":{
                var q=require(tradeins.capacityQuote(owner,new AppCapacityReplaceQuoteRequest(text(payload.get("targetProductNo")))));
                String wanted="CAPACITY_REPLACE".equals(kind)?"REPLACE_REQUIRED":"CAPACITY_AVAILABLE";
                if(!wanted.equals(q.decision()))throw new BizException(409,"CAPACITY_REPLACEMENT_SOURCE_CHANGED");
                var source=q.sourceDeviceId()==null?null:db.one("SELECT id,row_version,status,ownership_status FROM nx_user_device WHERE id=? AND user_id=?",q.sourceDeviceId(),owner);
                return values("businessAmountUsdt",q.payableUsdt().toPlainString(),"subtotalUsdt",money(q.payableUsdt()).toPlainString(),"targetProductId",q.targetProductId(),"source",source);
            }
            case "TRIAL_CONVERT":{
                var q=require(trials.externalPaymentPricing(owner,pricingAt));
                if(payload.get("quoteVersion")!=null&&!text(payload.get("quoteVersion")).equals(text(q.get("quoteVersion"))))throw new BizException(409,"TRIAL_QUOTE_CHANGED");
                return values("businessAmountUsdt",text(q.get("amountUsdt")),"subtotalUsdt",money(q.get("amountUsdt")).toPlainString(),"quoteVersion",q.get("quoteVersion"),"productNo",payload.get("productNo"),"trial",q);
            }
            default:throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        }
    }
    private static <T> T require(ApiResult<T> response){if(response==null||response.getCode()!=0)throw new BizException(response==null?503:response.getCode(),response==null?"PURCHASE_PAYMENT_UNAVAILABLE":response.getMessage());return response.getData();}
    private static Long optionalLong(Object value){return value==null?null:number(value);}
    public Map<String,Object> create(long owner,String key,String quoteId){
        if(key==null||key.isBlank()||key.length()>128)throw new BizException(422,"IDEMPOTENCY_KEY_REQUIRED");
        var originalQuote=db.one("SELECT * FROM nx_purchase_payment_quote WHERE quote_id=? AND user_id=?",quoteId,owner);
        if(originalQuote==null)throw new BizException(404,"PURCHASE_PAYMENT_QUOTE_NOT_FOUND");
        var existing=db.one("SELECT * FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",owner,originalQuote.get("kind"),key);
        if(existing!=null){if(!quoteId.equals(existing.get("quote_id")))throw new BizException(409,"IDEMPOTENCY_KEY_CONFLICT");return get(owner,text(existing.get("intent_no")));}
        boolean bsc=cregis(originalQuote.get("payment_method"));
        CregisNetwork network=bsc?network(originalQuote.get("payment_method")):null;
        ready(text(originalQuote.get("payment_method")));
        Map<String,Object> candidate=bsc?dedicatedAddress(network):null;
        // HDPay commits the immutable intent before create; BSC atomically dedicates an existing proven pool address.
        var reservation=tx(()->{
            if(bsc)cregisGate(network,true,true);
            user(owner,true);var quote=db.one("SELECT * FROM nx_purchase_payment_quote WHERE quote_id=? AND user_id=? FOR UPDATE",quoteId,owner);
            if(quote==null)throw new BizException(404,"PURCHASE_PAYMENT_QUOTE_NOT_FOUND");
            var prior=db.one("SELECT * FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",owner,quote.get("kind"),key);
            if(prior!=null){if(!quoteId.equals(prior.get("quote_id")))throw new BizException(409,"IDEMPOTENCY_KEY_CONFLICT");return values("intent",prior,"winner",false);}
            if(db.one("SELECT intent_no FROM nx_purchase_payment_intent WHERE quote_id=?",quoteId)!=null)throw new BizException(409,"PURCHASE_PAYMENT_QUOTE_ALREADY_USED");
            if(!instant(quote.get("expires_at")).isAfter(Instant.now()))throw new BizException(409,"PURCHASE_PAYMENT_QUOTE_EXPIRED");
            if(!Boolean.TRUE.equals(parse(quote.get("quote_json")).get("paymentFeesConfigured")))throw new BizException(503,"PURCHASE_PAYMENT_FEES_NOT_CONFIGURED");
            // Share the policy mutex with D1 publication until this intent commits its immutable fee snapshot.
            var feeVersion=db.one("SELECT version FROM nx_purchase_payment_policy WHERE id=1 FOR SHARE");
            if(feeVersion==null||number(feeVersion.get("version"))!=number(parse(quote.get("quote_json")).get("feePolicyVersion")))
                throw new BizException(409,"PURCHASE_PAYMENT_FEE_POLICY_CHANGED");
            if(!hash(pricing(owner,text(quote.get("kind")),parse(quote.get("payload_json")),Instant.parse(text(parse(quote.get("quote_json")).get("pricingAsOf"))))).equals(quote.get("pricing_hash")))throw new BizException(409,"PURCHASE_PAYMENT_PRICING_CHANGED");
            ready(text(quote.get("payment_method")));
            if(!db.rows("SELECT intent_no FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT','FUNDS_CONFIRMED','MANUAL_REVIEW') AND payload_json=CAST(? AS JSON)",owner,quote.get("kind"),quote.get("payload_json")).isEmpty())
                throw new BizException(409,"PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED");
            String no=id("PPI-");
            db.write("INSERT INTO nx_purchase_payment_intent(intent_no,quote_id,user_id,kind,payment_method,command_key,payload_json,quote_json,pricing_hash,status,expires_at,provider_project) VALUES(?,?,?,?,?,?,?,?,?,'CREATING',?,?)",
                    no,quoteId,owner,quote.get("kind"),quote.get("payment_method"),key,quote.get("payload_json"),quote.get("quote_json"),quote.get("pricing_hash"),quote.get("expires_at"),cr.getProjectId());
            if(bsc){
                var locked=db.one("SELECT id,address FROM nx_cregis_deposit_address WHERE id=? AND project_id=? AND chain_id=? AND state='UNASSIGNED' AND user_id IS NULL AND purpose='WALLET_TOPUP' FOR UPDATE",candidate.get("id"),cr.getProjectId(),network.chainId());
                if(locked==null||!candidate.get("address").equals(locked.get("address"))||!((Instant)candidate.get("proofExpiresAt")).isAfter(Instant.now()))
                    throw new BizException(409,"CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
                if(db.write("UPDATE nx_cregis_deposit_address SET state='DIRECT_PURCHASE',purpose='PURCHASE_DIRECT',purchase_intent_no=?,allocation_block=?,allocation_hash=?,updated_at=NOW(6) WHERE id=? AND state='UNASSIGNED' AND user_id IS NULL",no,candidate.get("allocationBlock"),candidate.get("allocationHash"),candidate.get("id"))!=1)
                    throw new BizException(409,"CREGIS_DIRECT_ADDRESS_ALLOCATION_RETRY");
                db.write("UPDATE nx_purchase_payment_intent SET address=?,scan_block=?,status='AWAITING_PAYMENT',version=version+1 WHERE intent_no=?",candidate.get("address"),number(candidate.get("allocationBlock"))+1,no);
            }
            return values("intent",db.one(SELECT,no),"winner",!bsc);
        });
        var intent=map(reservation.get("intent"));String no=text(intent.get("intent_no"));
        if(Boolean.TRUE.equals(reservation.get("winner")))submit(intent);
        return get(owner,no);
    }
    private void submit(Map<String,Object> intent){
        String no=text(intent.get("intent_no"));String method=text(intent.get("payment_method"));
        // A crash anywhere after this CAS is an unknown submission; never create another identity.
        if(db.write("UPDATE nx_purchase_payment_intent SET status='SUBMIT_UNKNOWN',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='CREATING'",no)!=1)return;
        try{
            if("HDPAY_BANK".equals(method)){
                var q=parse(intent.get("quote_json"));var page=hdGateway.createPayOrder(new HdPayGateway.CreatePayOrder(no,new BigDecimal(text(q.get("amountVnd"))),HdPayServerIp.requirePublicIpv4(hd.getServerIp())));
                if(page==null||!hd.isTrustedPaymentPage(page.url()))throw new BizException(503,"HDPAY_PAYMENT_PAGE_INVALID");
                db.write("UPDATE nx_purchase_payment_intent SET payment_url=?,status='AWAITING_PAYMENT',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='SUBMIT_UNKNOWN'",page.url(),no);
            }
        }catch(RuntimeException failure){
            db.write("UPDATE nx_purchase_payment_intent SET reason='PROVIDER_CREATE_OUTCOME_UNKNOWN',updated_at=NOW(6) WHERE intent_no=? AND status='SUBMIT_UNKNOWN'",no);
        }
    }
    public Map<String,Object> lookup(long owner,String kind,String key){
        var row=db.one("SELECT * FROM nx_purchase_payment_intent WHERE user_id=? AND kind=? AND command_key=?",owner,kind,key);
        if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");return get(owner,text(row.get("intent_no")));
    }
    /** Called only after the existing callback verifier accepted the provider signature. */
    public boolean observeHdCallback(HdPayCallbackVerifier.VerifiedCallback callback){
        var row=db.one(SELECT,callback.merchantOrderId());if(row==null)return false;
        if(!"HDPAY_BANK".equals(row.get("payment_method")))throw new BizException(409,"PURCHASE_PAYMENT_CALLBACK_RAIL_CONFLICT");
        refresh(row);return true;
    }
    /** Callback ownership is resolved against the dedicated intent address, never the topup pool. */
    public boolean observeBscCallback(Map<String,Object> callback){return observeCregisCallback(callback);}
    public boolean observeCregisCallback(Map<String,Object> callback){
        var network=CregisNetwork.fromAsset(text(callback.get("chain_id")),text(callback.get("token_id")));
        var row=db.one("SELECT * FROM nx_purchase_payment_intent WHERE address=LOWER(?) AND payment_method=? AND provider_project=?",callback.get("address"),method(network),cr.getProjectId());
        if(row==null)return false;refresh(row);return true;
    }
    public boolean ownsBscAddress(String address){return ownsCregisAddress(CregisNetwork.BEP20,address);}
    public boolean ownsCregisAddress(CregisNetwork network,String address){
        return db.one("SELECT intent_no FROM nx_purchase_payment_intent WHERE address=LOWER(?) AND payment_method=? AND provider_project=?",address,method(network),cr.getProjectId())!=null;
    }
    public Boolean materializeBscProviderRow(CregisGateway.DepositRow incoming){return materializeCregisProviderRow(incoming);}
    /** The provider asset selects the intent rail; the same hex address on another chain is a different owner. */
    public Boolean materializeCregisProviderRow(CregisGateway.DepositRow incoming){
        var network=CregisNetwork.fromAsset(incoming.chainId(),incoming.tokenId());
        var intent=db.one("SELECT * FROM nx_purchase_payment_intent WHERE address=LOWER(?) AND payment_method=? AND provider_project=?",incoming.address(),method(network),cr.getProjectId());
        if(intent==null)return null;if(incoming.status()!=1)return incoming.status()!=0;
        var matching=chain.scan(network,incoming.blockHeight(),incoming.blockHeight(),List.of(incoming.address())).stream()
                .filter(log->log.txid().equalsIgnoreCase(incoming.txid())&&log.amount().compareTo(incoming.amount())==0).toList();
        if(matching.size()!=1)return false;
        var log=matching.get(0);var proof=chain.verifyExact(network,log.txid(),log.logIndex(),incoming.address(),incoming.amount(),incoming.blockHeight());
        if(proof==null)return false;
        var fresh=crGateway.provider().depositsByTxid(network,incoming.txid()).stream().filter(row->row.cid()==incoming.cid()&&row.status()==1
                &&row.address().equalsIgnoreCase(incoming.address())&&row.amount().compareTo(incoming.amount())==0
                &&network.chainId().equals(row.chainId())&&network.tokenId().equalsIgnoreCase(row.tokenId())).toList();
        if(fresh.size()!=1)return false;
        recordBscArrival(intent,log,proof);refresh(db.one(SELECT,intent.get("intent_no")));return true;
    }
    public Map<String,Object> get(long owner,String no){
        var row=db.one(SELECT+" AND user_id=?",no,owner);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
        refresh(row);return view(db.one(SELECT,no),false);
    }
    private void refresh(Map<String,Object> row){
        if(row.get("receipt_json")!=null){
            if(cregis(row.get("payment_method")))try{auditCanonical(row);observeBsc(row);}catch(RuntimeException unavailable){}
            return;
        }
        try{if("HDPAY_BANK".equals(row.get("payment_method")))observeHd(row);else observeBsc(row);}
        catch(RuntimeException unavailable){/* Preserve the durable original identity and retry observation only. */}
        var current=db.one(SELECT,row.get("intent_no"));
        if(current.get("confirmed_at")!=null&&Set.of("FUNDS_CONFIRMED","MANUAL_REVIEW").contains(text(current.get("status"))))deliver(text(row.get("intent_no")),false);
        else if(current.get("confirmed_at")==null&&instant(current.get("expires_at")).isBefore(Instant.now()))
            db.write("UPDATE nx_purchase_payment_intent SET status='EXPIRED',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status='AWAITING_PAYMENT'",row.get("intent_no"));
    }
    private void observeHd(Map<String,Object> row){
        if(!hd.ready())return;String no=text(row.get("intent_no"));var observed=hdGateway.queryPayOrder(no);
        if(observed==null||!no.equals(observed.merchantOrderId())||observed.providerOrderId()==null||!"BANKQR".equalsIgnoreCase(observed.payType()))return;
        var quote=parse(row.get("quote_json"));
        if(observed.orderStatus()==3){
            if(row.get("confirmed_at")!=null)return;
            String reason=observed.transAmt()==null||new BigDecimal(text(quote.get("amountVnd"))).compareTo(observed.transAmt())!=0?"PAYMENT_AMOUNT_MISMATCH":
                    instant(row.get("expires_at")).isBefore(Instant.now())?"PAYMENT_LATE_REVIEW":"";
            confirm(no,observed.providerOrderId(),reason);
        }else if(observed.orderStatus()==1&&hd.isTrustedPaymentPage(observed.appLink())){
            db.write("UPDATE nx_purchase_payment_intent SET status='AWAITING_PAYMENT',payment_url=?,provider_reference=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status IN ('CREATING','SUBMIT_UNKNOWN','AWAITING_PAYMENT')",observed.appLink(),observed.providerOrderId(),no);
        }
    }
    private void observeBsc(Map<String,Object> row){
        var network=network(row.get("payment_method"));
        String address=text(row.get("address"));if(address.isBlank()||cr.getMode()!=CregisProperties.Mode.PROVIDER||number(row.get("provider_project"))!=cr.getProjectId())return;
        long from=number(row.get("scan_block")),head=chain.head(network).number()-Math.max(15,cr.confirmations(network))+1;
        if(head<from)return;long to=Math.min(head,from+Math.min(399,cr.getChainScanBlocks()-1));
        for(var incoming:chain.scan(network,from,to,List.of(address))){
            var proof=chain.verifyExact(network,incoming.txid(),incoming.logIndex(),address,incoming.amount(),incoming.blockNumber());if(proof==null)return;
            var trades=crGateway.provider().depositsByTxid(network,incoming.txid()).stream().filter(t->t.status()==1&&network.chainId().equals(t.chainId())
                    &&network.tokenId().equalsIgnoreCase(t.tokenId())&&address.equalsIgnoreCase(t.address())&&incoming.amount().compareTo(t.amount())==0).toList();
            if(trades.size()!=1)return;
            recordBscArrival(row,incoming,proof);
        }
        db.write("UPDATE nx_purchase_payment_intent SET scan_block=GREATEST(scan_block,?),updated_at=NOW(6) WHERE intent_no=?",to+1,row.get("intent_no"));
    }
    private void recordBscArrival(Map<String,Object> intent,BscDepositProof.Observation incoming,BscDepositProof.TransferProof proof){
        var network=network(intent.get("payment_method"));
        String no=text(intent.get("intent_no"));
        tx(()->{
            var locked=db.one(SELECT+" FOR UPDATE",no);
            var allocation=db.one("SELECT allocation_block,allocation_hash FROM nx_cregis_deposit_address WHERE purchase_intent_no=? AND chain_id=? AND purpose='PURCHASE_DIRECT' AND state='DIRECT_PURCHASE'",no,network.chainId());
            if(allocation==null||!text(allocation.get("allocation_hash")).equalsIgnoreCase(chain.blockHash(network,number(allocation.get("allocation_block")))))
                throw new BizException(409,"CREGIS_DIRECT_ALLOCATION_ANCHOR_INVALID");
            int inserted=db.write("INSERT IGNORE INTO nx_purchase_payment_arrival(intent_no,provider_project,chain_id,txid,log_index,amount,block_number,block_hash,confirmations,from_address,status) VALUES(?,?,?,?,?,?,?,?,?,?,'CONFIRMED')",no,cr.getProjectId(),network.chainId(),incoming.txid(),incoming.logIndex(),incoming.amount(),proof.blockNumber(),proof.blockHash(),proof.confirmations(),proof.fromAddress());
            if(inserted==1){
                BigDecimal expected=new BigDecimal(text(parse(locked.get("quote_json")).get("totalUsdt")));
                String reason=incoming.blockNumber()<=number(allocation.get("allocation_block"))?"PAYMENT_BEFORE_ALLOCATION":incoming.amount().compareTo(expected)!=0?"PAYMENT_AMOUNT_MISMATCH":instant(locked.get("expires_at")).isBefore(Instant.now())?"PAYMENT_LATE_REVIEW":"";
                if(locked.get("confirmed_at")!=null)reason="PAYMENT_MULTIPLE_ARRIVALS";
                if(!reason.isBlank())db.write("UPDATE nx_purchase_payment_arrival SET status='MANUAL_REVIEW' WHERE provider_project=? AND chain_id=? AND txid=? AND log_index=?",cr.getProjectId(),network.chainId(),incoming.txid(),incoming.logIndex());
                confirm(no,incoming.txid()+":"+incoming.logIndex(),reason);
            }return null;
        });
    }
    private void auditCanonical(Map<String,Object> intent){
        var network=network(intent.get("payment_method"));
        if("PAYMENT_REORG_REVIEW".equals(intent.get("reason")))return;
        for(var arrival:db.rows("SELECT * FROM nx_purchase_payment_arrival WHERE intent_no=?",intent.get("intent_no"))){
            var proof=chain.inspectCanonical(network,text(arrival.get("txid")),(int)number(arrival.get("log_index")),text(intent.get("address")),
                    new BigDecimal(text(arrival.get("amount"))),number(arrival.get("block_number")),text(arrival.get("block_hash")),
                    arrival.get("from_address")==null?null:text(arrival.get("from_address")));
            if(proof==null)continue;
            var current=proof.canonical();
            boolean changed=proof.positivelyVerifiedAbsent()||current!=null&&(current.logIndex()!=number(arrival.get("log_index"))
                    ||current.blockNumber()!=number(arrival.get("block_number"))||!current.blockHash().equalsIgnoreCase(text(arrival.get("block_hash"))));
            if(!changed)continue;
            // Positive chain evidence closes the existing financial risk gate; RPC failure alone never does.
            db.write("UPDATE nx_cregis_provision_gate SET state='BLOCKED',assign_enabled=0,credit_enabled=0,payout_enabled=0,version=version+1,switch_reason='PURCHASE_PAYMENT_REORG' WHERE id=1");
            tx(()->{
                db.write("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reason='PAYMENT_REORG_REVIEW',version=version+1,updated_at=NOW(6) WHERE intent_no=? AND receipt_json IS NOT NULL",intent.get("intent_no"));
                db.write("UPDATE nx_purchase_payment_arrival SET status='MANUAL_REVIEW' WHERE id=?",arrival.get("id"));
                audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_REORG_REVIEW").resourceType("PURCHASE_PAYMENT_INTENT")
                        .resourceId(text(intent.get("intent_no"))).userId(number(intent.get("user_id"))).actorType("SYSTEM").actorUsername("system:purchase-payment")
                        .result("MANUAL_REVIEW").riskLevel("HIGH").detail(values("txid",arrival.get("txid"),"logIndex",arrival.get("log_index"),"checkedHead",proof.checkedHead())).build());return null;
            });
        }
    }
    private void confirm(String no,String reference,String reason){
        db.write("UPDATE nx_purchase_payment_intent SET provider_reference=COALESCE(provider_reference,?),confirmed_at=COALESCE(confirmed_at,NOW(6)),status=?,reason=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND (receipt_json IS NULL OR ?<>'')",reference,reason.isBlank()?"FUNDS_CONFIRMED":"MANUAL_REVIEW",reason,no,reason);
    }
    private void deliver(String no,boolean explicit){
        var before=db.one(SELECT,no);
        if(before==null||!verifyFunds(before))return;
        boolean joined=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        try{tx(()->{
            if(cregis(before.get("payment_method")))cregisGate(network(before.get("payment_method")),false,true);
            var intent=db.one(SELECT+" FOR UPDATE",no);
            if(intent==null||intent.get("receipt_json")!=null||intent.get("confirmed_at")==null)return null;
            if(Set.of("PAYMENT_AMOUNT_MISMATCH","PAYMENT_LATE_REVIEW","PAYMENT_MULTIPLE_ARRIVALS","PAYMENT_BEFORE_ALLOCATION").contains(text(intent.get("reason"))))return null;
            if(!explicit&&"MANUAL_REVIEW".equals(intent.get("status")))return null;
            long owner=number(intent.get("user_id"));user(owner,true);
            String kind=text(intent.get("kind"));var payload=parse(intent.get("payload_json"));var quote=parse(intent.get("quote_json"));
            if(!hash(pricing(owner,kind,payload,Instant.parse(text(quote.get("pricingAsOf"))))).equals(intent.get("pricing_hash")))throw new BizException(409,"PURCHASE_PAYMENT_PRICING_CHANGED");
            var result=VerifiedPurchasePayment.settle(owner,text(intent.get("payment_method")),no,money(quote.get("subtotalUsdt")),Instant.parse(text(quote.get("pricingAsOf"))),()->fulfil(owner,kind,payload,no));
            Object receipt=require(result);
            Map<String,Object> canonical=jsonMapper.convertValue(receipt,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            canonical.put("walletDebitUsdt","0.00");canonical.put("amountUsdt",quote.get("businessAmountUsdt"));
            canonical.put("paymentSubtotalUsdt",quote.get("subtotalUsdt"));canonical.put("roundingAdjustmentUsdt",quote.get("roundingAdjustmentUsdt"));
            canonical.put("paymentMethod",intent.get("payment_method"));canonical.put("paymentRail",intent.get("payment_method"));
            String orderNo=text(canonical.get("orderNo"));
            if(orderNo.isBlank())throw new BizException(409,"PURCHASE_PAYMENT_RECEIPT_INVALID");
            if(db.write("UPDATE nx_order SET payment_no=?,updated_at=NOW(6) WHERE order_no=? AND user_id=? AND payment_status='PAID'",no,orderNo,owner)!=1)
                throw new BizException(409,"PURCHASE_PAYMENT_ORDER_LINK_CONFLICT");
            if(db.write("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,currency,payment_status,signature_status,raw_callback,paid_at,created_at,updated_at,is_deleted) VALUES(?,?,?,?,?,?,'USDT','PAID','VERIFIED',?,NOW(6),NOW(6),NOW(6),0)",
                    no,orderNo,owner,intent.get("payment_method"),intent.get("provider_reference"),quote.get("businessAmountUsdt"),json(values("intentNo",no,"quoteId",intent.get("quote_id"),"paymentMethod",intent.get("payment_method"))))!=1)
                throw new BizException(409,"PURCHASE_PAYMENT_RECORD_CONFLICT");
            if(db.write("UPDATE nx_purchase_payment_intent SET status='DELIVERED',reason='',receipt_json=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND status IN ('FUNDS_CONFIRMED','MANUAL_REVIEW')",json(canonical),no)!=1)throw new BizException(409,"PURCHASE_PAYMENT_DELIVERY_CONFLICT");
            audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_DELIVERED").resourceType("PURCHASE_PAYMENT_INTENT")
                    .resourceId(no).bizNo(no).userId(owner).actorType("SYSTEM").actorUsername("system:purchase-payment")
                    .result("SUCCESS").riskLevel("HIGH").detail(values("paymentMethod",intent.get("payment_method"),"quote",quote,"providerReference",intent.get("provider_reference"))).build());
            return null;
        });}catch(RuntimeException failure){
            if(joined)throw failure;
            String code=failure.getMessage();if(code==null||!code.matches("[A-Z0-9_:]{1,128}"))code="PURCHASE_PAYMENT_DELIVERY_BLOCKED";
            String reason=code;
            tx(()->{db.write("UPDATE nx_purchase_payment_intent SET status='MANUAL_REVIEW',reason=?,version=version+1,updated_at=NOW(6) WHERE intent_no=? AND confirmed_at IS NOT NULL AND status<>'DELIVERED'",reason,no);return null;});
        }
    }
    private boolean verifyFunds(Map<String,Object> intent){
        if(intent.get("confirmed_at")==null)return false;
        try{
            if("HDPAY_BANK".equals(intent.get("payment_method"))){
                var fresh=hdGateway.queryPayOrder(text(intent.get("intent_no")));
                return fresh!=null&&fresh.orderStatus()==3&&text(intent.get("intent_no")).equals(fresh.merchantOrderId())
                        &&text(intent.get("provider_reference")).equals(fresh.providerOrderId())&&"BANKQR".equalsIgnoreCase(fresh.payType())
                        &&fresh.transAmt()!=null&&new BigDecimal(text(parse(intent.get("quote_json")).get("amountVnd"))).compareTo(fresh.transAmt())==0;
            }
            var network=network(intent.get("payment_method"));
            var arrivals=db.rows("SELECT * FROM nx_purchase_payment_arrival WHERE intent_no=? AND chain_id=?",intent.get("intent_no"),network.chainId());
            if(arrivals.size()!=1||number(intent.get("provider_project"))!=cr.getProjectId())return false;
            var incoming=arrivals.get(0);String txid=text(incoming.get("txid"));BigDecimal amount=new BigDecimal(text(incoming.get("amount")));
            var proof=chain.verifyExact(network,txid,(int)number(incoming.get("log_index")),text(intent.get("address")),amount,number(incoming.get("block_number")));
            if(proof==null||!proof.blockHash().equalsIgnoreCase(text(incoming.get("block_hash"))))return false;
            var trades=crGateway.provider().depositsByTxid(network,txid).stream().filter(t->t.status()==1&&network.chainId().equals(t.chainId())
                    &&network.tokenId().equalsIgnoreCase(t.tokenId())&&text(intent.get("address")).equalsIgnoreCase(t.address())&&amount.compareTo(t.amount())==0).toList();
            return trades.size()==1&&amount.compareTo(new BigDecimal(text(parse(intent.get("quote_json")).get("totalUsdt"))))==0;
        }catch(RuntimeException unavailable){return false;}
    }
    private ApiResult<?> fulfil(long owner,String kind,Map<String,Object> payload,String no){
        return switch(kind){
            case "ORDER_PAY"->orders.settleVerifiedExternal(owner,text(payload.get("orderNo")));
            case "TRADE_IN"->tradeins.settleVerifiedExternal(owner,"TRADE_IN",no,jsonMapper.convertValue(payload,AppTradeinSubmitRequest.class),null,null);
            case "CAPACITY_REPLACE"->tradeins.settleVerifiedExternal(owner,"CAPACITY_REPLACE",no,null,jsonMapper.convertValue(payload,AppCapacityReplaceSubmitRequest.class),null);
            case "CAPACITY_KEEP"->tradeins.settleVerifiedExternal(owner,"CAPACITY_KEEP",no,null,null,jsonMapper.convertValue(payload,AppCapacityKeepSubmitRequest.class));
            case "TRIAL_CONVERT"->trials.settleVerifiedExternal(owner,text(payload.get("productNo")),new BigDecimal(text(payload.get("expectedAmountUsdt"))),text(payload.get("quoteVersion")));
            default->throw new BizException(422,"PURCHASE_PAYMENT_KIND_INVALID");
        };
    }
    @Scheduled(fixedDelayString="${nexion.finance.purchase-payment-observe-ms:30000}")
    public void scheduled(){
        for(var row:db.rows("SELECT * FROM nx_purchase_payment_intent WHERE status<>'DELIVERED' OR payment_method IN ('CREGIS_USDT_BSC','CREGIS_USDT_ERC20') ORDER BY COALESCE(observed_at,created_at) LIMIT 50")){
            try{refresh(row);}catch(RuntimeException unavailable){/* One unavailable rail cannot stop others. */}
            finally{db.write("UPDATE nx_purchase_payment_intent SET observed_at=NOW(6) WHERE intent_no=?",row.get("intent_no"));}
        }
    }
    public Map<String,Object> records(String status,String method,int page,int size){
        int limit=Math.min(100,Math.max(1,size)),number=Math.max(1,page);String s=status==null?"":status,m=method==null?"":method;
        String where=" WHERE (?='' OR status=?) AND (?='' OR payment_method=?)";
        var total=db.one("SELECT COUNT(*) total FROM nx_purchase_payment_intent"+where,s,s,m,m);
        var rows=db.rows("SELECT * FROM nx_purchase_payment_intent"+where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",s,s,m,m,limit,(number-1)*limit);
        return values("items",rows.stream().map(row->view(row,true)).toList(),"total",total.get("total"),"pageNum",number,"pageSize",limit);
    }
    public Map<String,Object> detail(String no){var row=db.one(SELECT,no);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");return view(row,true);}
    @SuppressWarnings("unchecked")
    public Map<String,Object> revalidate(String no,String key,Map<String,Object> request){
        PurchasePaymentPolicyService.reason(request);String actor=AdminActorResolver.resolve("admin");
        return commands.executeRetained("D1:PURCHASE_REVALIDATE:"+actor,key,hash(values("intentNo",no,"request",request)),Map.class,()->{
            var snapshot=db.one(SELECT,no);
            if(snapshot!=null&&cregis(snapshot.get("payment_method")))cregisGate(network(snapshot.get("payment_method")),false,true);
            var row=db.one(SELECT+" FOR UPDATE",no);if(row==null)throw new BizException(404,"PURCHASE_PAYMENT_INTENT_NOT_FOUND");
            if(number(row.get("version"))!=number(request.get("expectedVersion")))throw new BizException(409,"PURCHASE_PAYMENT_REVALIDATE_VERSION_CONFLICT");
            refresh(row);deliver(no,true);var result=detail(no);
            audit.recordRequired(AuditLogWriteRequest.builder().action("PURCHASE_PAYMENT_REVALIDATE").resourceType("PURCHASE_PAYMENT_INTENT").resourceId(no)
                    .actorType("ADMIN").actorUsername(actor).riskLevel("HIGH").result("SUCCESS").detail(values("reason",request.get("reason"),"result",result)).build());
            return result;
        });
    }
    private Map<String,Object> view(Map<String,Object> row,boolean admin){
        var result=parse(row.get("quote_json"));result.putAll(values("intentNo",row.get("intent_no"),"commandKey",row.get("command_key"),"status",row.get("status"),
                "reason",row.get("reason"),"version",row.get("version")));
        if(row.get("payment_url")!=null&&"AWAITING_PAYMENT".equals(row.get("status")))result.put("paymentUrl",row.get("payment_url"));
        if(row.get("address")!=null)result.put("address",row.get("address"));
        result.put("fulfilmentStatus",row.get("receipt_json")==null?"NOT_DELIVERED":"DELIVERED");
        if(row.get("receipt_json")!=null)result.put("receipt",parse(row.get("receipt_json")));
        if(admin)result.putAll(values("userId",text(row.get("user_id")),"request",parse(row.get("payload_json")),"providerReference",row.get("provider_reference"),
                "providerActualFeeUsdt",row.get("provider_actual_fee_usdt")==null?null:new BigDecimal(text(row.get("provider_actual_fee_usdt"))).toPlainString(),
                "createdAt",row.get("created_at")==null?null:instant(row.get("created_at")).toString(),"updatedAt",row.get("updated_at")==null?null:instant(row.get("updated_at")).toString()));
        if(admin)result.put("arrivals",db.rows("SELECT txid,log_index,CAST(amount AS CHAR) amount,block_number,block_hash,confirmations,status FROM nx_purchase_payment_arrival WHERE intent_no=? ORDER BY id",row.get("intent_no")));
        return result;
    }
}
