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
@RequiredArgsConstructor
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

    public String groupForEvent(Long id){return mapper.groupForEvent(id);}

    @Transactional(rollbackFor=Exception.class)
    public int settle(String kind,String ref,Long claimedUserId) {
        if(!KINDS.contains(kind)||ref==null||ref.isBlank()||ref.length()>96)throw new BizException(422,"DIRECT_REFERRAL_SOURCE_INVALID");
        var scope=policies.scope();
        // The immutable business row serializes all envelopes, including new event IDs and changed sponsors.
        var source="direct_purchase".equals(kind)?mapper.purchase(ref):mapper.earning(ref);
        if(source==null)throw new BizException(409,"DIRECT_REFERRAL_SOURCE_NOT_FOUND");
        if(claimedUserId==null||!claimedUserId.equals(source.userId()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_USER_MISMATCH");
        if(!Integer.valueOf(scope.sandbox()).equals(source.sandbox()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_ENVIRONMENT_MISMATCH");
        if(mapper.existing(scope.sourceEnvironment(),scope.runId(),kind,ref)!=null)return 0;
        if(source.sourceOccurredAt()==null)throw new BizException(409,"DIRECT_REFERRAL_SOURCE_NOT_CONFIRMED");
        var policy=policies.at(source.sourceOccurredAt());
        var rule="direct_purchase".equals(kind)?policy.purchase():policy.deviceEarning();
        String reason="";
        if(!Integer.valueOf(1).equals(source.valid()))reason="SOURCE_NOT_ELIGIBLE";
        else if(Integer.valueOf(1).equals(source.refunded()))reason="SOURCE_REFUNDED";
        else if(!rule.enabled())reason="POLICY_DISABLED_OR_NOT_EFFECTIVE";
        else if(source.sponsorUserId()==null||source.sponsorUserId().equals(source.userId()))reason="NO_ELIGIBLE_DIRECT_SPONSOR";
        else if(mapper.lockUser(source.sponsorUserId(),scope.sandbox())==null||mapper.lockWallet(source.sponsorUserId(),scope.sandbox())==null)reason="SPONSOR_UNAVAILABLE";
        BigDecimal price=null,basis=zero(source.amountUsdt()),usdt=BigDecimal.ZERO,nex=BigDecimal.ZERO;
        if(reason.isEmpty()){
            price=policies.price();
            if(price==null)throw new BizException(503,"DIRECT_REFERRAL_PRICE_UNAVAILABLE");
            if("direct_device_earning".equals(kind))basis=basis.add(zero(source.amountNex()).multiply(price));
            if(basis.signum()<=0)reason="ZERO_PAID_SOURCE";
            else {
                var amounts=rule.calculate(basis,price);usdt=amounts.usdt();nex=amounts.nex();
                if(!amounts.payable())reason="BELOW_DUAL_ASSET_PRECISION";
            }
        }
        String status=reason.isEmpty()?"COOLING":"REJECTED";
        if("REJECTED".equals(status)){usdt=BigDecimal.ZERO;nex=BigDecimal.ZERO;}
        if(reason.isEmpty()&&held(source.sponsorUserId(),kind)) {status="FROZEN";reason="SPONSOR_RISK_OR_SUSPENSION";}
        String no="DR-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        LocalDateTime releaseAt=source.sourceOccurredAt().plusDays(rule.coolingDays());
        var row=new LinkedHashMap<String,Object>();
        row.put("settlementNo",no);row.put("sourceEnvironment",scope.sourceEnvironment());row.put("runId",scope.runId());
        row.put("sourceType",kind);row.put("sourceRef",ref);row.put("sourceUserId",source.userId());row.put("beneficiaryUserId",source.sponsorUserId());
        row.put("sourceUserName",mask(source.sourceUserName()));row.put("sourceDeviceId",source.sourceDeviceId());row.put("sourceOccurredAt",source.sourceOccurredAt());
        row.put("policyVersion",policy.policyVersion());row.put("policySnapshot",encode(policy));row.put("basisUsdt",basis);row.put("nexUsdtPrice",price);
        row.put("amountUsdt",usdt);row.put("amountNex",nex);row.put("status",status);row.put("releaseAt",releaseAt);row.put("reason",reason);
        if(mapper.insertSettlement(row)!=1)throw new BizException(409,"DIRECT_REFERRAL_INSERT_CONFLICT");
        if(!"REJECTED".equals(status)){
            if(mapper.insertCommission(source.sponsorUserId(),kind,source.userId(),mask(source.sourceUserName()),ref,basis,usdt,BigDecimal.ZERO,"USDT",status,releaseAt,no)!=1)throw conflict();
            Long usdtId=mapper.lastId();
            if(mapper.insertCommission(source.sponsorUserId(),kind,source.userId(),mask(source.sourceUserName()),ref,basis,BigDecimal.ZERO,nex,"NEX",status,releaseAt,no)!=1)throw conflict();
            Long nexId=mapper.lastId();
            if(mapper.linkEvents(no,usdtId,nexId)!=1)throw conflict();
            if("COOLING".equals(status)&&!releaseAt.isAfter(now()))release(no);
            return 1;
        }
        return 0;
    }

    private boolean held(Long id,String kind){
        if(mapper.suspended(id,kind)>0)return true;
        var cluster=releaseMapper.riskCluster(id);
        return cluster!=null&&(Set.of("detected","flagged","frozen").contains(String.valueOf(cluster.status()).toLowerCase(Locale.ROOT))
                ||(cluster.accountCount()!=null&&cluster.accountCount()>=risk.pendingFrom()));
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean release(String no){
        lockPurchaseSource(no);
        var row=lock(no);String status=text(row,"status");
        if(row.get("credited_at")!=null)return false;
        if(!Set.of("COOLING","UNLOCKED").contains(status)||flag(row,"reversal_recorded"))throw new BizException(409,"DIRECT_REFERRAL_RELEASE_STATE_CONFLICT");
        if(time(row,"release_at").isAfter(now()))throw new BizException(409,"DIRECT_REFERRAL_COOLING_ACTIVE");
        Long id=number(row,"beneficiary_user_id");int sandbox=scope(row);
        if(mapper.lockUser(id,sandbox)==null||mapper.lockWallet(id,sandbox)==null)throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_UNAVAILABLE");
        if(held(id,text(row,"source_type")))throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
        var events=mapper.lockEvents(number(row,"usdt_event_id"),number(row,"nex_event_id"));
        if(events.size()!=2||events.stream().anyMatch(e->!Set.of("COOLING","PENDING","UNLOCKED").contains(text(e,"status"))))throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
        if("direct_purchase".equals(text(row,"source_type"))){
            var source=mapper.purchase(text(row,"source_ref"));
            if(source==null||Integer.valueOf(1).equals(source.refunded()))throw new BizException(409,"DIRECT_REFERRAL_SOURCE_REFUNDED");
        }
        String type=sandbox==1?"MOCK_DIRECT_REFERRAL":"DIRECT_REFERRAL";
        for(String asset:List.of("USDT","NEX")){
            BigDecimal amount=money(row,"amount_"+asset.toLowerCase(Locale.ROOT));
            earnings.creditReward(id,type,no+":"+asset,asset,amount,text(row,"source_environment"),no+":"+asset);
            ledger.postLedgerEntry(no+"-RELEASE",id,"TEAM_COMMISSION",asset,"IN",amount,"SUCCESS","Direct referral wallet release | "+no);
        }
        if(mapper.withdrawableReleaseCount(id,no+":USDT",no+":NEX")!=2)throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
        if(mapper.credited(no)!=1)throw conflict();
        mapper.eventStatus(number(row,"usdt_event_id"),number(row,"nex_event_id"),"UNLOCKED");
        // Two financial rows share one canonical commission fact; this never becomes earnings.credited.
        outbox.publish("DIRECT_REFERRAL",no,"commission.paid",Map.of("userId",id,"kind",text(row,"source_type"),
                "currency","USDT","amount",money(row,"amount_usdt"),"sourceUserId",number(row,"source_user_id"),
                "layer",1,"orderNo",text(row,"source_ref"),"commissionEventId",number(row,"usdt_event_id")));
        return true;
    }

    @Transactional(rollbackFor=Exception.class)
    public void changeStatus(Long eventId,String target,Long expectedVersion){
        String no=mapper.groupForEvent(eventId);if(no==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");
        lockPurchaseSource(no);
        var row=lock(no);var events=mapper.lockEvents(number(row,"usdt_event_id"),number(row,"nex_event_id"));
        var requested=events.stream().filter(e->eventId.equals(number(e,"id"))).findFirst().orElseThrow(DirectReferralService::conflict);
        if(expectedVersion==null||!expectedVersion.equals(number(requested,"version")))throw new BizException(409,"F5_COMMISSION_VERSION_CONFLICT");
        String current=text(row,"status");
        if("UNLOCKED".equals(target)){release(no);return;}
        if("REJECTED".equals(target))throw new BizException(409,"DIRECT_REFERRAL_REVERSE_REQUIRES_F5_COMMAND");
        if(("FROZEN".equals(target)&&"COOLING".equals(current))||("COOLING".equals(target)&&"FROZEN".equals(current))){
            if("COOLING".equals(target)&&held(number(row,"beneficiary_user_id"),text(row,"source_type")))throw new BizException(409,"DIRECT_REFERRAL_SPONSOR_FROZEN");
            mapper.groupStatus(no,target,"F5_GROUP_STATUS");mapper.eventStatus(number(row,"usdt_event_id"),number(row,"nex_event_id"),target);return;
        }
        throw new BizException(409,"DIRECT_REFERRAL_GROUP_STATE_CONFLICT");
    }

    @Transactional(rollbackFor=Exception.class)
    public void refund(String orderNo){
        var source=mapper.purchase(orderNo);
        if(source==null||!Integer.valueOf(1).equals(source.refunded()))throw new BizException(409,"DIRECT_REFERRAL_REFUND_NOT_CONFIRMED");
        for(String no:mapper.purchaseGroups(orderNo))reverse(no,BigDecimal.ONE);
    }

    /** Current order facts support full refunds only; partial inputs must never move funds. */
    @Transactional(rollbackFor=Exception.class)
    public void reverse(String no,BigDecimal ratio){
        if(ratio==null||ratio.compareTo(BigDecimal.ONE)!=0)throw new BizException(422,"DIRECT_REFERRAL_REFUND_RATIO_INVALID");
        lockPurchaseSource(no);
        var row=lock(no);scope(row);ratio=ratio.max(money(row,"refund_ratio"));
        BigDecimal targetUsdt=money(row,"amount_usdt").multiply(ratio).setScale(6,RoundingMode.DOWN);
        BigDecimal targetNex=money(row,"amount_nex").multiply(ratio).setScale(6,RoundingMode.DOWN);
        BigDecimal recoveredUsdt=money(row,"recovered_usdt"),recoveredNex=money(row,"recovered_nex");
        BigDecimal pendingUsdt=BigDecimal.ZERO,pendingNex=BigDecimal.ZERO;
        if(row.get("credited_at")!=null){
            Long id=number(row,"beneficiary_user_id");int sandbox=scope(row);
            // Preserve wallet recovery for inactive accounts; refund must not depend on their login status.
            var wallet=mapper.lockWallet(id,sandbox);if(wallet==null)throw new BizException(409,"DIRECT_REFERRAL_WALLET_UNAVAILABLE");
            BigDecimal dueUsdt=targetUsdt.subtract(recoveredUsdt).max(BigDecimal.ZERO),dueNex=targetNex.subtract(recoveredNex).max(BigDecimal.ZERO);
            BigDecimal takeUsdt=dueUsdt.min(wallet.usdt().max(BigDecimal.ZERO)),takeNex=dueNex.min(wallet.nex().max(BigDecimal.ZERO));
            if(takeUsdt.signum()>0||takeNex.signum()>0){
                if(mapper.debit(id,sandbox,takeUsdt,takeNex)!=1)throw conflict();
                if(takeUsdt.signum()>0)ledger.postLedgerEntry(no+"-RECOVER-USDT-"+recoveredUsdt.add(takeUsdt).toPlainString(),id,"TEAM_COMMISSION","USDT","OUT",takeUsdt,"SUCCESS","Direct referral recovery | "+no);
                if(takeNex.signum()>0)ledger.postLedgerEntry(no+"-RECOVER-NEX-"+recoveredNex.add(takeNex).toPlainString(),id,"TEAM_COMMISSION","NEX","OUT",takeNex,"SUCCESS","Direct referral recovery | "+no);
            }
            recoveredUsdt=recoveredUsdt.add(takeUsdt);recoveredNex=recoveredNex.add(takeNex);
            pendingUsdt=targetUsdt.subtract(recoveredUsdt).max(BigDecimal.ZERO);pendingNex=targetNex.subtract(recoveredNex).max(BigDecimal.ZERO);
            if(ratio.compareTo(BigDecimal.ONE)==0)mapper.reverseReleaseEntries(id,no+":USDT",no+":NEX");
        }
        String status=pendingUsdt.signum()>0||pendingNex.signum()>0?"RECOVERY_PENDING":"REVERSED";
        mapper.recovery(no,ratio,recoveredUsdt,recoveredNex,pendingUsdt,pendingNex,status);
        mapper.eventStatus(number(row,"usdt_event_id"),number(row,"nex_event_id"),status);
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> reverseEvent(Long eventId){
        String no=mapper.groupForEvent(eventId);if(no==null)throw new BizException(404,"DIRECT_REFERRAL_GROUP_NOT_FOUND");
        lockPurchaseSource(no);
        var row=lock(no);
        if(!Set.of("COOLING","FROZEN","UNLOCKED").contains(text(row,"status"))||flag(row,"reversal_recorded"))
            throw new BizException(409,"COMMISSION_REVERSE_STATE_CONFLICT");
        reverse(no,BigDecimal.ONE);
        var result=view(mapper.group(no));
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

    public Map<String,Object> eventSnapshot(Long eventId){String no=mapper.groupForEvent(eventId);return no==null?Map.of():view(mapper.group(no));}

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
        value.put("amountUSDT",money(row,"amount_usdt"));value.put("amountNEX",money(row,"amount_nex"));value.put("status",text(row,"status").toLowerCase(Locale.ROOT));
        value.put("recoveryPendingUSDT",money(row,"recovery_pending_usdt"));value.put("recoveryPendingNEX",money(row,"recovery_pending_nex"));value.put("reversalRecorded",flag(row,"reversal_recorded"));
        value.put("ts",time(row,"created_at").atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli());
        value.put("unlockAt",time(row,"release_at").atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli());return value;
    }
    private void lockPurchaseSource(String no){var row=mapper.group(no);if(row!=null&&"direct_purchase".equals(text(row,"source_type")))mapper.purchase(text(row,"source_ref"));}
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
