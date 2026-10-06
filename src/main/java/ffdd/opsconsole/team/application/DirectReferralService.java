package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor(onConstructor_=@org.springframework.beans.factory.annotation.Autowired)
public class DirectReferralService {
    public static final Set<String> KINDS=Set.of("direct_purchase","direct_device_earning");
    private final DirectReferralMapper mapper;
    private final DirectReferralPolicyService policies;
    private final EarningsReleaseService earnings;
    private final EarningsReleaseMapper releaseMapper;
    private final RiskReleaseParamsService risk;
    private final TreasuryLedgerPostingFacade ledger;
    private final EventOutboxService outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final UnilevelCommissionService unilevel;
    private final org.springframework.beans.factory.ObjectProvider<DirectReferralService> self;
    public DirectReferralService(DirectReferralMapper mapper,DirectReferralPolicyService policies,EarningsReleaseService earnings,
            EarningsReleaseMapper releaseMapper,RiskReleaseParamsService risk,TreasuryLedgerPostingFacade ledger,EventOutboxService outbox,ObjectMapper json,Clock clock){
        this(mapper,policies,earnings,releaseMapper,risk,ledger,outbox,json,clock,null,null);
    }

    public String groupForEvent(Long id){return mapper.groupForEvent(id);}

    public int settle(String kind,String ref,Long claimedUserId) {
        var target=self==null?this:self.getObject();
        int prepared=target.prepareSettlement(kind,ref,claimedUserId);
        target.resumeSource(kind,ref);return prepared;
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean release(String no){return releaseSelected(no,null);}
    @Transactional(rollbackFor=Exception.class)
    public void reverse(String no,BigDecimal ratio){
        if(ratio==null||ratio.compareTo(BigDecimal.ONE)!=0)throw new BizException(422,"DIRECT_REFERRAL_REFUND_RATIO_INVALID");
        lockPurchaseSource(no);var row=lock(no);recoverTargets(no,row,money(row,"amount_usdt"),money(row,"amount_nex"));
    }
    @Transactional(rollbackFor=Exception.class)
    public void retryRecovery(String no){
        lockPurchaseSource(no);var row=lock(no);recoverTargets(no,row,money(row,"cancelled_usdt"),money(row,"cancelled_nex"));
    }
    private void recoverTargets(String no,Map<String,Object> row,BigDecimal targetUsdt,BigDecimal targetNex){
        scope(row);targetUsdt=targetUsdt.max(money(row,"cancelled_usdt"));targetNex=targetNex.max(money(row,"cancelled_nex"));
        BigDecimal recoveredUsdt=money(row,"recovered_usdt"),recoveredNex=money(row,"recovered_nex");
        Long id=number(row,"beneficiary_user_id");int sandbox=scope(row);
        BigDecimal dueUsdt=assetCredited(row,"usdt")?targetUsdt.subtract(recoveredUsdt).max(BigDecimal.ZERO):BigDecimal.ZERO;
        BigDecimal dueNex=assetCredited(row,"nex")?targetNex.subtract(recoveredNex).max(BigDecimal.ZERO):BigDecimal.ZERO;
        var wallet=mapper.lockWallet(id,sandbox);if(wallet==null)wallet=new DirectReferralMapper.Wallet(BigDecimal.ZERO,BigDecimal.ZERO);
        BigDecimal takeUsdt=dueUsdt.min(zero(wallet.usdt()).max(BigDecimal.ZERO)),takeNex=dueNex.min(zero(wallet.nex()).max(BigDecimal.ZERO));
        if(takeUsdt.signum()>0||takeNex.signum()>0){
            if(mapper.debit(id,sandbox,takeUsdt,takeNex)!=1)throw conflict();
            if(takeUsdt.signum()>0)ledger.postLedgerEntry(no+"-RECOVER-USDT-"+recoveredUsdt.add(takeUsdt).toPlainString(),id,"TEAM_COMMISSION","USDT","OUT",takeUsdt,"SUCCESS","Direct referral recovery | "+no);
            if(takeNex.signum()>0)ledger.postLedgerEntry(no+"-RECOVER-NEX-"+recoveredNex.add(takeNex).toPlainString(),id,"TEAM_COMMISSION","NEX","OUT",takeNex,"SUCCESS","Direct referral recovery | "+no);
        }
        recoveredUsdt=recoveredUsdt.add(takeUsdt);recoveredNex=recoveredNex.add(takeNex);
        BigDecimal pendingUsdt=dueUsdt.subtract(takeUsdt),pendingNex=dueNex.subtract(takeNex);
        boolean complete=targetUsdt.compareTo(money(row,"amount_usdt"))>=0&&targetNex.compareTo(money(row,"amount_nex"))>=0;
        String status=complete?(pendingUsdt.signum()>0||pendingNex.signum()>0?"RECOVERY_PENDING":"REVERSED"):text(row,"status");
        mapper.assetRecovery(no,targetUsdt,targetNex,complete?1:0,complete?BigDecimal.ONE:BigDecimal.ZERO,recoveredUsdt,recoveredNex,pendingUsdt,pendingNex,status);
        if(targetUsdt.signum()>0&&row.get("usdt_event_id")!=null)mapper.recoveredEventStatus(number(row,"usdt_event_id"),pendingUsdt.signum()>0?"RECOVERY_PENDING":"REVERSED");
        if(targetNex.signum()>0&&row.get("nex_event_id")!=null)mapper.recoveredEventStatus(number(row,"nex_event_id"),pendingNex.signum()>0?"RECOVERY_PENDING":"REVERSED");
        mapper.reverseReleaseEntries(id,targetUsdt.signum()>0?no+":USDT":no+":NONE",targetNex.signum()>0?no+":NEX":no+":NONE");
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean releaseEvent(Long eventId){
        String no=mapper.groupForEvent(eventId);if(no==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");
        var row=mapper.group(no);return releaseSelected(no,"network".equals(text(row,"source_type"))?eventId:null);
    }

    private boolean releaseSelected(String no,Long selectedEvent){
        lockPurchaseSource(no);var row=lock(no);String status=text(row,"status");
        boolean networkAsset=selectedEvent!=null&&"network".equals(text(row,"source_type"));
        if(!(networkAsset?Set.of("COOLING","FROZEN","UNLOCKED"):Set.of("COOLING","UNLOCKED")).contains(status)||flag(row,"reversal_recorded"))throw new BizException(409,"DIRECT_REFERRAL_RELEASE_STATE_CONFLICT");
        if(time(row,"release_at").isAfter(now()))throw new BizException(409,"DIRECT_REFERRAL_COOLING_ACTIVE");
        if(Set.of("direct_purchase","network").contains(text(row,"source_type"))){
            var source=mapper.purchase(text(row,"source_ref"));
            if(source==null||Integer.valueOf(1).equals(source.refunded()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_REFUNDED");
            var scope=policies.scope();var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),text(row,"source_ref"));
            if(order!=null&&"CONFLICT".equals(text(order,"settlement_mode")))throw new BizException(409,"UNILEVEL_SOURCE_REQUIRES_REVIEW");
        }
        Long id=number(row,"beneficiary_user_id");int sandbox=scope(row);
        if(mapper.lockUser(id,sandbox)==null||mapper.lockWallet(id,sandbox)==null)throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_UNAVAILABLE");
        if(held(id,text(row,"source_type")))throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
        var events=mapper.lockEvents(number(row,"usdt_event_id"),number(row,"nex_event_id"));
        int expected=(money(row,"amount_usdt").signum()>0?1:0)+(money(row,"amount_nex").signum()>0?1:0);
        if(events.size()!=expected)throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
        String type=sandbox==1?"MOCK_DIRECT_REFERRAL":"DIRECT_REFERRAL";boolean changed=false,newUsdt=false;
        for(var event:events){
            if(selectedEvent!=null&&!selectedEvent.equals(number(event,"id")))continue;
            String asset=text(event,"currency"),suffix=asset.toLowerCase(Locale.ROOT);BigDecimal amount=money(row,"amount_"+suffix);
            if(amount.signum()<=0||money(row,"cancelled_"+suffix).compareTo(amount)>=0||assetCredited(row,suffix))continue;
            if(!Set.of("COOLING","PENDING","UNLOCKED").contains(text(event,"status")))throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
            earnings.creditReward(id,type,no+":"+asset,asset,amount,text(row,"source_environment"),no+":"+asset);
            ledger.postLedgerEntry(no+"-RELEASE",id,"TEAM_COMMISSION",asset,"IN",amount,"SUCCESS","Direct referral wallet release | "+no);
            mapper.creditedAsset(no,asset);mapper.recoveredEventStatus(number(event,"id"),"UNLOCKED");changed=true;if("USDT".equals(asset))newUsdt=true;
        }
        var after=mapper.group(no);int credited=0;boolean all=true;
        for(String suffix:List.of("usdt","nex")){
            BigDecimal amount=money(after,"amount_"+suffix);if(amount.signum()<=0||money(after,"cancelled_"+suffix).compareTo(amount)>=0)continue;
            if(assetCredited(after,suffix))credited++;else all=false;
        }
        if(mapper.withdrawableReleaseCount(id,no+":USDT",no+":NEX")!=credited)throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
        if(all&&row.get("credited_at")==null)mapper.credited(no);
        if(newUsdt)outbox.publish("DIRECT_REFERRAL",no,"commission.paid",Map.of("userId",id,"kind",text(row,"source_type"),
                "currency","USDT","amount",money(row,"amount_usdt"),"sourceUserId",number(row,"source_user_id"),
                "layer",row.getOrDefault("layer_no",1),"orderNo",text(row,"source_ref"),"commissionEventId",number(row,"usdt_event_id")));
        return changed;
    }
    private boolean assetCredited(Map<String,Object> row,String asset){return row.get("credited_"+asset+"_at")!=null||(row.get("credited_at")!=null&&row.get("credited_usdt_at")==null&&row.get("credited_nex_at")==null);}
    @Transactional(rollbackFor=Exception.class)
    public int prepareSettlement(String kind,String ref,Long claimedUserId) {
        if(!KINDS.contains(kind)||ref==null||ref.isBlank()||ref.length()>96)throw new BizException(422,"DIRECT_REFERRAL_SOURCE_INVALID");
        var scope=policies.scope();
        // The immutable business row serializes all envelopes, including new event IDs and changed sponsors.
        var source="direct_purchase".equals(kind)?mapper.purchase(ref):mapper.earning(ref);
        if(source==null)throw new BizException(409,"DIRECT_REFERRAL_SOURCE_NOT_FOUND");
        if(claimedUserId==null||!claimedUserId.equals(source.userId()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_USER_MISMATCH");
        if(!Integer.valueOf(scope.sandbox()).equals(source.sandbox()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_ENVIRONMENT_MISMATCH");
        if(source.sourceOccurredAt()==null)throw new BizException(409,"DIRECT_REFERRAL_SOURCE_NOT_CONFIRMED");
        if("direct_purchase".equals(kind)&&(policies.sevenLayerActive(source.sourceOccurredAt())||mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),ref)!=null||mapper.historicalNetwork(ref)>0||mapper.historicalDirect(ref)>0))return settleSeven(ref,source,scope);
        String existing=mapper.existing(scope.sourceEnvironment(),scope.runId(),kind,ref);
        if(existing!=null)return 0;
        var policy=policies.atForUpdate(source.sourceOccurredAt());
        var rule="direct_purchase".equals(kind)?policy.purchase():policy.deviceEarning();
        String reason="";
        if(!Integer.valueOf(1).equals(source.valid()))reason="SOURCE_NOT_ELIGIBLE";
        else if(Integer.valueOf(1).equals(source.refunded()))reason="SOURCE_REFUNDED";
        else if(!rule.enabled())reason="POLICY_DISABLED_OR_NOT_EFFECTIVE";
        else if(source.sponsorUserId()==null||source.sponsorUserId().equals(source.userId()))reason="NO_ELIGIBLE_DIRECT_SPONSOR";
        else if(mapper.lockUser(source.sponsorUserId(),scope.sandbox())==null||mapper.lockWallet(source.sponsorUserId(),scope.sandbox())==null)reason="SPONSOR_UNAVAILABLE";
        else if(zero(source.amountUsdt()).signum()<=0&&zero(source.amountNex()).signum()<=0)reason="ZERO_PAID_SOURCE";
        BigDecimal price=null,basis=zero(source.amountUsdt()),usdt=BigDecimal.ZERO,nex=BigDecimal.ZERO;
        String status=reason.isEmpty()?"WAITING_CALCULATION":"REJECTED";
        if("REJECTED".equals(status)){usdt=BigDecimal.ZERO;nex=BigDecimal.ZERO;}
        String no="DR-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        LocalDateTime releaseAt=source.sourceOccurredAt().plusDays(rule.coolingDays());
        var row=new LinkedHashMap<String,Object>();
        row.put("settlementNo",no);row.put("sourceEnvironment",scope.sourceEnvironment());row.put("runId",scope.runId());
        row.put("sourceType",kind);row.put("sourceRef",ref);row.put("sourceUserId",source.userId());row.put("beneficiaryUserId",source.sponsorUserId());
        row.put("sourceUserName",mask(source.sourceUserName()));row.put("sourceDeviceId",source.sourceDeviceId());row.put("sourceOccurredAt",source.sourceOccurredAt());
        row.put("policyVersion",policy.policyVersion());row.put("policySnapshot",encode(Map.of("policy",policy,"sourceAmountNex",zero(source.amountNex()),"orderBudget",false)));row.put("basisUsdt",basis);row.put("nexUsdtPrice",price);
        row.put("layerNo",1);row.put("priceLockedAt",null);
        row.put("amountUsdt",usdt);row.put("amountNex",nex);row.put("status",status);row.put("releaseAt",releaseAt);row.put("reason",reason);
        if(mapper.insertSettlement(row)!=1)throw new BizException(409,"DIRECT_REFERRAL_INSERT_CONFLICT");
        if(!"REJECTED".equals(status))return 1;
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int settleSeven(String ref,DirectReferralMapper.Source source,DirectReferralPolicyService.Scope scope) {
        if(!Integer.valueOf(1).equals(source.valid()))throw new BizException(409,"UNILEVEL_ORDER_NOT_PAID");
        var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),ref);
        if(order==null){
            long revision=policies.lockSevenLayerRevision();
            int network=mapper.historicalNetwork(ref),direct=mapper.historicalDirect(ref);
            String mode=network>0&&direct>0?"CONFLICT":network>0?"LEGACY_7":direct>0?"DIRECT_ONLY_V1":"SEVEN_V2";
            var policy=policies.atForUpdate(source.sourceOccurredAt());
            var plan="SEVEN_V2".equals(mode)?requireUnilevel().prepareBudget(source.userId(),source.sponsorUserId(),zero(mapper.purchaseSubtotal(ref))):Map.<String,Object>of();
            if("SEVEN_V2".equals(mode))for(var item:(List<Map<String,Object>>)plan.get("layers")){
                Long id=(Long)item.get("beneficiaryUserId");if(id!=null&&mapper.sourceMember(id,scope.sandbox())==null)throw new BizException(409,"UNILEVEL_CHAIN_ENVIRONMENT_MISMATCH");
            }
            var value=new LinkedHashMap<String,Object>();value.put("sourceEnvironment",scope.sourceEnvironment());value.put("runId",scope.runId());value.put("orderNo",ref);
            value.put("sourceUserId",source.userId());value.put("sourceOccurredAt",source.sourceOccurredAt());value.put("preparedAt",now());value.put("settlementMode",mode);
            value.put("splitEnabled",policy.schemaVersion()==2&&policy.purchase().enabled());value.put("policyVersion",policy.policyVersion());value.put("sevenLayerRevision",revision);
            value.put("policySnapshot",encode(policy));value.put("chainAndRules",encode(plan));value.put("allocatedBudgetUsdt",plan.getOrDefault("allocatedBudgetUsdt",BigDecimal.ZERO));
            value.put("status","CONFLICT".equals(mode)?"REQUIRES_REVIEW":"PREPARED");value.put("reason","CONFLICT".equals(mode)?"MIXED_HISTORICAL_MODES":"");
            if(mapper.insertOrder(value)!=1)throw conflict();order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),ref);
        }
        if(!"SEVEN_V2".equals(text(order,"settlement_mode")))return 0;
        if(flag(order,"refund_confirmed")||Integer.valueOf(1).equals(source.refunded())) {mapper.orderStatus(number(order,"id"),"REFUNDED",1);return 0;}
        var groups=mapper.orderGroups(scope.sourceEnvironment(),scope.runId(),ref);
        if(!groups.isEmpty())return 0;
        try{
            var plan=json.readTree(text(order,"chain_and_rules"));var policy=json.readValue(text(order,"policy_snapshot"),DirectReferralPolicy.class);
            LocalDateTime releaseAt=time(order,"prepared_at").plusDays(plan.path("coolingDays").asInt());int emitted=0;
            for(var item:plan.path("layers")) {
                if(item.path("budgetUsdt").decimalValue().signum()<=0)continue;
                int layer=item.path("layer").asInt();Long beneficiary=item.path("beneficiaryUserId").longValue();
                boolean split=layer==1&&flag(order,"split_enabled");String kind=split?"direct_purchase":"network";
                var row=new LinkedHashMap<String,Object>();String no="DR-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
                row.put("settlementNo",no);row.put("sourceEnvironment",scope.sourceEnvironment());row.put("runId",scope.runId());row.put("sourceType",kind);row.put("sourceRef",ref);row.put("layerNo",layer);
                row.put("sourceUserId",source.userId());row.put("beneficiaryUserId",beneficiary);row.put("sourceUserName",mask(source.sourceUserName()));row.put("sourceDeviceId",null);
                row.put("sourceOccurredAt",time(order,"source_occurred_at"));row.put("policyVersion",number(order,"policy_version"));row.put("policySnapshot",encode(Map.of("policy",policy,"orderBudget",true,"orderBasisUsdt",plan.path("orderBasisUsdt").decimalValue(),"sourceAmountNex",BigDecimal.ZERO)));
                row.put("basisUsdt",item.path("budgetUsdt").decimalValue());row.put("nexUsdtPrice",null);row.put("priceLockedAt",null);
                row.put("amountUsdt",split?BigDecimal.ZERO:item.path("budgetUsdt").decimalValue());row.put("amountNex",split?BigDecimal.ZERO:item.path("legacyNex").decimalValue());
                row.put("status",split?"WAITING_CALCULATION":held(beneficiary,kind)?"FROZEN":"COOLING");row.put("releaseAt",releaseAt);row.put("reason",split?"PRICE_PENDING":"");
                if(mapper.insertSettlement(row)!=1)throw conflict();
                emitted++;
            }
            return emitted;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("UNILEVEL_SNAPSHOT_INVALID",e);}
    }
    private UnilevelCommissionService requireUnilevel(){if(unilevel==null)throw new BizException(503,"UNILEVEL_ENGINE_UNAVAILABLE");return unilevel;}

    public void resumeSource(String kind,String ref) {
        var scope=policies.scope();var target=self==null?this:self.getObject();
        if("direct_purchase".equals(kind)){var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),ref);if(order!=null&&"CONFLICT".equals(text(order,"settlement_mode")))throw new BizException(409,"UNILEVEL_SOURCE_REQUIRES_REVIEW");}
        if("direct_purchase".equals(kind)){var source=mapper.purchase(ref);if(source!=null&&Integer.valueOf(1).equals(source.refunded())){target.refund(ref);return;}}
        List<String> groups="direct_purchase".equals(kind)?mapper.orderGroups(scope.sourceEnvironment(),scope.runId(),ref):
                Optional.ofNullable(mapper.existing(scope.sourceEnvironment(),scope.runId(),kind,ref)).map(List::of).orElse(List.of());
        RuntimeException failed=null;
        for(String no:groups)try{target.resumeGroup(no);}catch(RuntimeException failure){failed=failure;}
        // Deep layers remain independently recoverable when the L1 price/asset write fails.
        if(failed!=null)throw failed;
    }
    public void resumeGroup(String no) {
        var target=self==null?this:self.getObject();target.calculateWaiting(no);target.materializeGroup(no);
    }
    @Transactional(rollbackFor=Exception.class)
    public void materializeGroup(String no){
        lockPurchaseSource(no);var row=lock(no);
        if(Set.of("COOLING","FROZEN").contains(text(row,"status"))&&row.get("usdt_event_id")==null)materialize(no);
    }

    /** Must precede an event/wallet lock, including F5 reissues whose order_no was changed. */
    public void lockEventSource(Long eventId,boolean requirePaid) {
        String order=mapper.sourceOrderForEvent(eventId);if(order==null)return;
        var source=mapper.purchase(order);
        if(requirePaid&&(source==null||Integer.valueOf(1).equals(source.refunded())))throw new BizException(409,"COMMISSION_SOURCE_REFUNDED");
    }
    /** All batches acquire original orders in the same order before any event or wallet lock. */
    public void lockEventSources(Collection<Long> eventIds,boolean requirePaid) {
        var orders=new TreeSet<String>();
        for(Long eventId:eventIds){String order=mapper.sourceOrderForEvent(eventId);if(order!=null)orders.add(order);}
        for(String order:orders){var source=mapper.purchase(order);if(requirePaid&&(source==null||Integer.valueOf(1).equals(source.refunded())))throw new BizException(409,"COMMISSION_SOURCE_REFUNDED");}
    }
    private void recoverReissues(String order,DirectReferralPolicyService.Scope scope) {
        for(var event:mapper.reissueDescendants(order)) {
            Long id=number(event,"id"),user=number(event,"user_id");String asset=text(event,"currency");
            var prior=mapper.eventRecovery(id);BigDecimal target=flag(event,"credited")&&!flag(event,"reversed")?money(event,"amount"):BigDecimal.ZERO;
            BigDecimal recovered=money(prior==null?Map.of():prior,"recovered_amount"),due=target.subtract(recovered).max(BigDecimal.ZERO);
            var wallet=mapper.lockWallet(user,scope.sandbox());BigDecimal balance=wallet==null?BigDecimal.ZERO:"USDT".equals(asset)?wallet.usdt():wallet.nex();
            BigDecimal take=due.min(zero(balance).max(BigDecimal.ZERO));
            if(take.signum()>0){
                if(mapper.debit(user,scope.sandbox(),"USDT".equals(asset)?take:BigDecimal.ZERO,"NEX".equals(asset)?take:BigDecimal.ZERO)!=1)throw conflict();
                recovered=recovered.add(take);ledger.postLedgerEntry("DR-REISSUE-"+id+"-RECOVER-"+recovered.toPlainString(),user,"TEAM_COMMISSION",asset,"OUT",take,"SUCCESS","Seven layer reissue recovery | "+order);
            }
            BigDecimal pending=target.subtract(recovered).max(BigDecimal.ZERO);
            mapper.saveEventRecovery(id,order,scope.sourceEnvironment(),scope.runId(),user,asset,target,recovered,pending);
            mapper.recoveredEventStatus(id,pending.signum()>0?"RECOVERY_PENDING":"REVERSED");
        }
    }

    /** The source, sponsor, rules and deadline are already durable; a missing price is a recoverable fact. */
    @Transactional(rollbackFor=Exception.class)
    public boolean calculateWaiting(String no) {
        lockPurchaseSource(no);var row=lock(no);
        if(!"WAITING_CALCULATION".equals(text(row,"status")))return false;
        if("direct_purchase".equals(text(row,"source_type"))){var source=mapper.purchase(text(row,"source_ref"));if(source==null||Integer.valueOf(1).equals(source.refunded())){reverse(no,BigDecimal.ONE);return false;}}
        BigDecimal price=policies.price();if(price==null)return false;
        try{
            var snapshot=json.readTree(text(row,"policy_snapshot"));var policy=json.treeToValue(snapshot.has("policy")?snapshot.get("policy"):snapshot,DirectReferralPolicy.class);
            boolean budget=snapshot.path("orderBudget").asBoolean();String kind=text(row,"source_type");var rule="direct_device_earning".equals(kind)?policy.deviceEarning():policy.purchase();
            BigDecimal basis=money(row,"basis_usdt");if("direct_device_earning".equals(kind))basis=basis.add(snapshot.path("sourceAmountNex").decimalValue().multiply(price));
            var amounts=(budget?new DirectReferralPolicy.Rule(true,new BigDecimal("100"),rule.usdtSharePct(),rule.coolingDays()):rule).calculate(basis,price);
            String status=!amounts.payable()?"REJECTED":held(number(row,"beneficiary_user_id"),kind)?"FROZEN":"COOLING";
            if(mapper.calculated(no,price,now(),basis,amounts.payable()?amounts.usdt():BigDecimal.ZERO,amounts.payable()?amounts.nex():BigDecimal.ZERO,status,!amounts.payable()?"BELOW_DUAL_ASSET_PRECISION":"")!=1)throw conflict();
            return true;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("DIRECT_REFERRAL_SNAPSHOT_INVALID",e);}
    }
    private void materialize(String no) {
        var row=lock(no);if(row.get("usdt_event_id")!=null)return;
        Long usdtId=null,nexId=null;int layer=row.get("layer_no") instanceof Number n?n.intValue():1;
        BigDecimal orderBasis=money(row,"basis_usdt");
        try{var snapshot=json.readTree(text(row,"policy_snapshot"));if(snapshot.path("orderBudget").asBoolean()){
            if(snapshot.has("orderBasisUsdt"))orderBasis=snapshot.path("orderBasisUsdt").decimalValue();
            else {var scope=policies.scope();var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),text(row,"source_ref"));var plan=json.readTree(text(order,"chain_and_rules"));if(!plan.has("orderBasisUsdt"))throw new BizException(503,"UNILEVEL_SNAPSHOT_BASIS_MISSING");orderBasis=plan.path("orderBasisUsdt").decimalValue();}
        }}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("UNILEVEL_SNAPSHOT_INVALID",e);}
        for(String asset:List.of("USDT","NEX")){
            BigDecimal amount=money(row,"amount_"+asset.toLowerCase(Locale.ROOT));if(amount.signum()<=0)continue;
            if(mapper.insertLayerCommission(number(row,"beneficiary_user_id"),text(row,"source_type"),number(row,"source_user_id"),text(row,"source_user_name"),layer,text(row,"source_ref"),orderBasis,"USDT".equals(asset)?amount:BigDecimal.ZERO,"NEX".equals(asset)?amount:BigDecimal.ZERO,asset,text(row,"status"),time(row,"release_at"),no)!=1)throw conflict();
            if("USDT".equals(asset))usdtId=mapper.lastId();else nexId=mapper.lastId();
        }
        if(mapper.linkEvents(no,usdtId,nexId)!=1)throw conflict();
        if("COOLING".equals(text(row,"status"))&&!time(row,"release_at").isAfter(now()))release(no);
    }

    private boolean held(Long id,String kind){
        if(mapper.suspended(id,kind)>0)return true;
        var cluster=releaseMapper.riskCluster(id);
        return cluster!=null&&(Set.of("detected","flagged","frozen").contains(String.valueOf(cluster.status()).toLowerCase(Locale.ROOT))
                ||(cluster.accountCount()!=null&&cluster.accountCount()>=risk.pendingFrom()));
    }


    @Transactional(rollbackFor=Exception.class)
    public void changeStatus(Long eventId,String target,Long expectedVersion){
        String no=mapper.groupForEvent(eventId);if(no==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");
        lockPurchaseSource(no);
        var row=lock(no);var events=mapper.lockEvents(number(row,"usdt_event_id"),number(row,"nex_event_id"));
        var requested=events.stream().filter(e->eventId.equals(number(e,"id"))).findFirst().orElseThrow(DirectReferralService::conflict);
        if(expectedVersion==null||!expectedVersion.equals(number(requested,"version")))throw new BizException(409,"F5_COMMISSION_VERSION_CONFLICT");
        String current=text(row,"status");
        if("network".equals(text(row,"source_type"))){
            String from=text(requested,"status");
            if("UNLOCKED".equals(target)){releaseSelected(no,eventId);return;}
            if(("FROZEN".equals(target)&&Set.of("COOLING","PENDING").contains(from))||("COOLING".equals(target)&&"FROZEN".equals(from))){
                if("COOLING".equals(target)&&held(number(row,"beneficiary_user_id"),"network"))throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
                mapper.recoveredEventStatus(eventId,target);return;
            }
            throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
        }
        if("UNLOCKED".equals(target)){release(no);return;}
        if("REJECTED".equals(target))throw new BizException(409,"DIRECT_REFERRAL_REVERSE_REQUIRES_F5_COMMAND");
        if(("FROZEN".equals(target)&&"COOLING".equals(current))||("COOLING".equals(target)&&"FROZEN".equals(current))){
            if("COOLING".equals(target)&&held(number(row,"beneficiary_user_id"),text(row,"source_type")))throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
            mapper.groupStatus(no,target,"F5_GROUP_STATUS");mapper.eventStatus(number(row,"usdt_event_id"),number(row,"nex_event_id"),target);return;
        }
        throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
    }

    public void refund(String orderNo){
        var target=self==null?this:self.getObject();var groups=target.confirmRefund(orderNo);
        RuntimeException failed=null;
        for(String no:groups)try{target.reverse(no,BigDecimal.ONE);}catch(RuntimeException failure){target.markRecoveryFailure(no);failed=failure;}
        try{target.recoverOrderReissues(orderNo);}catch(RuntimeException failure){failed=failure;}
        if(failed!=null)throw failed;
    }
    @Transactional(rollbackFor=Exception.class)
    public List<String> confirmRefund(String orderNo){
        var source=mapper.purchase(orderNo);
        if(source==null||!Integer.valueOf(1).equals(source.refunded()))throw new BizException(409,"DIRECT_REFERRAL_REFUND_NOT_CONFIRMED");
        var scope=policies.scope();var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),orderNo);
        if(order!=null&&"SEVEN_V2".equals(text(order,"settlement_mode"))){
            mapper.orderStatus(number(order,"id"),"REFUNDED",1);
            return mapper.orderGroups(scope.sourceEnvironment(),scope.runId(),orderNo);
        }else return mapper.purchaseGroups(orderNo);
    }
    @Transactional(rollbackFor=Exception.class)
    public void recoverOrderReissues(String orderNo){var scope=policies.scope();var source=mapper.purchase(orderNo);var order=mapper.lockOrder(scope.sourceEnvironment(),scope.runId(),orderNo);
        if(source==null||!Integer.valueOf(1).equals(source.refunded())||order==null||!"SEVEN_V2".equals(text(order,"settlement_mode")))return;
        recoverReissues(orderNo,scope);
    }
    @Transactional(rollbackFor=Exception.class)
    public void markRecoveryFailure(String no){lockPurchaseSource(no);var row=lock(no);
        BigDecimal usdt=money(row,"recovered_usdt"),nex=money(row,"recovered_nex");
        BigDecimal pendingUsdt=assetCredited(row,"usdt")?money(row,"amount_usdt").subtract(usdt).max(BigDecimal.ZERO):BigDecimal.ZERO;
        BigDecimal pendingNex=assetCredited(row,"nex")?money(row,"amount_nex").subtract(nex).max(BigDecimal.ZERO):BigDecimal.ZERO;
        mapper.assetRecovery(no,money(row,"amount_usdt"),money(row,"amount_nex"),1,BigDecimal.ONE,usdt,nex,pendingUsdt,pendingNex,pendingUsdt.signum()>0||pendingNex.signum()>0?"RECOVERY_PENDING":"REVERSED");
        if(row.get("usdt_event_id")!=null)mapper.recoveredEventStatus(number(row,"usdt_event_id"),pendingUsdt.signum()>0?"RECOVERY_PENDING":"REVERSED");
        if(row.get("nex_event_id")!=null)mapper.recoveredEventStatus(number(row,"nex_event_id"),pendingNex.signum()>0?"RECOVERY_PENDING":"REVERSED");
    }


    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> reverseEvent(Long eventId){
        String no=mapper.groupForEvent(eventId);if(no==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");
        lockPurchaseSource(no);
        var row=lock(no);
        if(!Set.of("COOLING","FROZEN","UNLOCKED").contains(text(row,"status"))||flag(row,"reversal_recorded"))
            throw new BizException(409,"COMMISSION_REVERSE_STATE_CONFLICT");
        if("network".equals(text(row,"source_type"))){
            var event=mapper.lockEvents(number(row,"usdt_event_id"),number(row,"nex_event_id")).stream().filter(item->eventId.equals(number(item,"id"))).findFirst().orElseThrow(DirectReferralService::conflict);
            if(!Set.of("COOLING","PENDING","FROZEN","UNLOCKED","AVAILABLE").contains(text(event,"status")))throw new BizException(409,"COMMISSION_REVERSE_STATE_CONFLICT");
            boolean usdt="USDT".equals(text(event,"currency"));recoverTargets(no,row,usdt?money(row,"amount_usdt"):money(row,"cancelled_usdt"),usdt?money(row,"cancelled_nex"):money(row,"amount_nex"));
        }else reverse(no,BigDecimal.ONE);
        var result=eventSnapshot(eventId);
        Long userId=number(row,"beneficiary_user_id");
        result.put("userId",userId);
        result.put("ledgerBizNos",mapper.recoveryLedgerRefs(no,userId));
        return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public int suspend(Long id,String kind,boolean suspended){
        var expected=policies.scope();int changed=0;
        for(String no:mapper.openGroups(id,kind,expected.sourceEnvironment(),expected.runId())){
            var row=lock(no);
            if(!Set.of("COOLING","FROZEN").contains(text(row,"status")))continue;
            String status=suspended?"FROZEN":"COOLING";
            if(!suspended&&held(id,kind))continue;
            mapper.groupStatus(no,status,suspended?"F5_GROUP_SUSPENDED":"F5_GROUP_RESUMED");
            changed+=mapper.eventStatus(number(row,"usdt_event_id"),number(row,"nex_event_id"),status);
        }
        return changed;
    }

    public Map<String,Object> eventSnapshot(Long eventId){String no=mapper.groupForEvent(eventId);if(no==null)return Map.of();var value=view(mapper.group(no));if("network".equals(value.get("kind"))){var event=mapper.eventView(eventId);value.put("status",text(event,"status").toLowerCase(Locale.ROOT));value.put("operationScope","single_event");}else value.put("operationScope","dual_asset_group");return value;}
    public List<Map<String,Object>> pendingForOps(String kind,Long user,String cohort){var scope=policies.scope();return mapper.pendingForOps(scope.sourceEnvironment(),scope.runId(),kind,user,cohort).stream().map(this::view).toList();}

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> insightsV2(Long userId,String period,long page,long pageSize,String rawSnapshot,String filter){
        if(!Set.of("all","purchase","device_earning","direct_device_earning").contains(filter))throw new BizException(422,"DIRECT_REFERRAL_QUERY_INVALID");
        var result=insights(userId,period,page,pageSize,rawSnapshot);var scope=policies.scope();Instant snapshot=Instant.parse((String)result.get("snapshotAt"));
        LocalDate day=snapshot.atZone(ZoneOffset.UTC).toLocalDate();LocalDate first=switch(period){case "today"->day;case "week"->day.minusDays(day.getDayOfWeek().getValue()-1);case "month"->day.withDayOfMonth(1);default->null;};
        LocalDateTime from=first==null?null:LocalDateTime.ofInstant(first.atStartOfDay(ZoneOffset.UTC).toInstant(),DateTimeFormatConfig.BUSINESS_ZONE);
        LocalDateTime at=LocalDateTime.ofInstant(snapshot,DateTimeFormatConfig.BUSINESS_ZONE);String kind="all".equals(filter)?null:"purchase".equals(filter)?"direct_purchase":"direct_device_earning";
        var sums=mapper.filteredSums(userId,scope.sourceEnvironment(),scope.runId(),at,from,kind);var summary=new LinkedHashMap<String,Object>();long count=0;
        for(String key:List.of("amountUSDT","amountNEX","creditedUSDT","creditedNEX","pendingUSDT","pendingNEX"))summary.put(key,sums.stream().map(row->money(row,key)).reduce(BigDecimal.ZERO,BigDecimal::add));
        var split=new LinkedHashMap<String,Object>();for(String sourceKind:List.of("direct_purchase","direct_device_earning")){
            var sum=sums.stream().filter(row->sourceKind.equals(text(row,"kind"))).findFirst().orElse(Map.of());long n=sum.get("count") instanceof Number number?number.longValue():0;count+=n;
            split.put("direct_purchase".equals(sourceKind)?"purchase":"deviceEarning",Map.of("amountUSDT",money(sum,"amountUSDT"),"amountNEX",money(sum,"amountNEX"),"count",n));
        }
        summary.put("count",count);result.put("schemaVersion",2);result.put("kind",filter);result.put("summary",summary);result.put("split",split);result.put("totalRows",count);
        result.put("events",mapper.filteredEvents(userId,scope.sourceEnvironment(),scope.runId(),at,from,kind,(page-1)*pageSize,pageSize).stream().map(this::view).toList());return result;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> insights(Long userId,String period,long page,long pageSize,String rawSnapshot){
        if(!Set.of("today","week","month","all").contains(period)||page<1||pageSize<1||pageSize>100||page>Integer.MAX_VALUE)throw new BizException(422,"DIRECT_REFERRAL_QUERY_INVALID");
        var scope=policies.scope();if(mapper.activeUser(userId,scope.sandbox())==null)throw new BizException(403,"USER_AUTH_REQUIRED");
        Instant snapshot;
        try{snapshot=rawSnapshot==null||rawSnapshot.isBlank()?clock.instant():Instant.parse(rawSnapshot);}catch(RuntimeException e){throw new BizException(422,"TEAM_SNAPSHOT_INVALID");}
        if(snapshot.isAfter(clock.instant().plusSeconds(5)))throw new BizException(422,"TEAM_SNAPSHOT_INVALID");
        LocalDate day=snapshot.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first=switch(period){case "today"->day;case "week"->day.minusDays(day.getDayOfWeek().getValue()-1);case "month"->day.withDayOfMonth(1);default->null;};
        LocalDateTime from=first==null?null:LocalDateTime.ofInstant(first.atStartOfDay(ZoneOffset.UTC).toInstant(),DateTimeFormatConfig.BUSINESS_ZONE);
        LocalDateTime boundary=LocalDateTime.ofInstant(snapshot,DateTimeFormatConfig.BUSINESS_ZONE);
        var sums=mapper.sums(userId,scope.sourceEnvironment(),scope.runId(),boundary,from);
        Map<String,Object> split=new LinkedHashMap<>();long total=0;
        for(String kind:List.of("direct_purchase","direct_device_earning")){
            var sum=sums.stream().filter(s->kind.equals(text(s,"kind"))).findFirst().orElse(Map.of());
            long count=sum.get("count") instanceof Number n?n.longValue():0;total+=count;
            split.put("direct_purchase".equals(kind)?"purchase":"deviceEarning",Map.of("amountUSDT",money(sum,"amountUSDT"),"amountNEX",money(sum,"amountNEX"),"count",count));
        }
        var result=new LinkedHashMap<String,Object>();result.put("source","server");result.put("serverCanonical",true);result.put("sourceEnvironment",scope.sourceEnvironment());result.put("runId",scope.runId());
        result.put("period",period);result.put("page",page);result.put("pageSize",pageSize);result.put("totalRows",total);result.put("snapshotAt",snapshot.toString());result.put("generatedAt",clock.instant().toString());result.put("split",split);
        result.put("events",mapper.events(userId,scope.sourceEnvironment(),scope.runId(),boundary,from,(page-1)*pageSize,pageSize).stream().map(this::view).toList());return result;
    }

    private Map<String,Object> view(Map<String,Object> row){
        Map<String,Object> value=new LinkedHashMap<>();String no=text(row,"settlement_no");
        value.put("id",no);value.put("settlementNo",no);value.put("kind",text(row,"source_type"));value.put("sourceUserName",text(row,"source_user_name"));
        value.put("sourceRef",text(row,"source_ref"));value.put("sourceDeviceId",row.get("source_device_id")==null?null:String.valueOf(row.get("source_device_id")));
        value.put("policyVersion",number(row,"policy_version"));value.put("basisUsdt",money(row,"basis_usdt"));value.put("nexUsdtPrice",row.get("nex_usdt_price"));
        value.put("layer",row.getOrDefault("layer_no",1));value.put("creditedAt",row.get("credited_at"));value.put("priceLockedAt",row.get("price_locked_at"));value.put("reason",text(row,"reason"));
        value.put("creditedUSDTAt",row.get("credited_usdt_at"));value.put("creditedNEXAt",row.get("credited_nex_at"));value.put("cancelledUSDT",money(row,"cancelled_usdt"));value.put("cancelledNEX",money(row,"cancelled_nex"));
        value.put("amountUSDT",money(row,"amount_usdt"));value.put("amountNEX",money(row,"amount_nex"));value.put("status",text(row,"status").toLowerCase(Locale.ROOT));
        value.put("recoveryPendingUSDT",money(row,"recovery_pending_usdt"));value.put("recoveryPendingNEX",money(row,"recovery_pending_nex"));value.put("reversalRecorded",flag(row,"reversal_recorded"));
        value.put("ts",time(row,"created_at").atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli());
        value.put("unlockAt",time(row,"release_at").atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli());return value;
    }
    private void lockPurchaseSource(String no){var row=mapper.group(no);if(row!=null&&Set.of("direct_purchase","network").contains(text(row,"source_type")))mapper.purchase(text(row,"source_ref"));}
    private Map<String,Object> lock(String no){var row=mapper.lockGroup(no);if(row==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");scope(row);return row;}
    private int scope(Map<String,Object> row){var expected=policies.scope();if(!expected.sourceEnvironment().equals(text(row,"source_environment"))||!expected.runId().equals(text(row,"run_id")))throw new BizException(409,"DIRECT_REFERRAL_ENVIRONMENT_MISMATCH");return expected.sandbox();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String mask(String name){return name==null||name.isBlank()?"Member***":name.substring(0,Math.min(3,name.length()))+"***";}
    private static BigDecimal zero(BigDecimal n){return n==null?BigDecimal.ZERO:n;}
    private static BigDecimal money(Map<String,Object> row,String key){Object n=row.get(key);return n==null?BigDecimal.ZERO:new BigDecimal(n.toString());}
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?"":row.get(key).toString();}
    private static Long number(Map<String,Object> row,String key){Object n=row.get(key);return n==null?null:((Number)n).longValue();}
    private static boolean flag(Map<String,Object> row,String key){Object n=row.get(key);return Boolean.TRUE.equals(n)||(n instanceof Number v&&v.intValue()!=0);}
    private static LocalDateTime time(Map<String,Object> row,String key){Object t=row.get(key);return t instanceof java.sql.Timestamp ts?ts.toLocalDateTime():(LocalDateTime)t;}
    private static BizException conflict(){return new BizException(409,"DIRECT_REFERRAL_WRITE_CONFLICT");}
}
