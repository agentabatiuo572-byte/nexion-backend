package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor
public class CregisDepositSwitchService {
    private final CregisProperties config;
    private final CregisDepositMapper db;
    private final AuditLogService audit;
    private final PlatformTransactionManager txManager;
    private final CregisPayoutControl payouts;
    private final AdminMapper admins;
    private final AdminPermissionCache permissions;

    public List<Map<String,Object>> cases() {
        return config.getMode()==CregisProperties.Mode.PROVIDER?db.switchCases(config.getProjectId()):List.of();
    }
    public Map<String,Object> proposeNetwork(long actor,long version,CregisNetwork network,long networkVersion,boolean deposit,String reason) {
        require(actor,reason);
        if(config.getMode()!=CregisProperties.Mode.PROVIDER || version<0 || networkVersion<0)throw fail("CREGIS_SWITCH_CONFIG_INVALID");
        return new TransactionTemplate(txManager).execute(ignored->{
            var gate=db.lockProvisionGate();var current=db.lockNetworkSwitch(network);
            if(gate==null || current==null || number(gate,"version")!=version || number(current,"version")!=networkVersion)
                throw fail("CREGIS_SWITCH_VERSION_CHANGED");
            if(deposit==on(current.get("depositEnabled")) && "IDLE".equals(gate.get("state")) && on(gate.get("assignEnabled")) && on(gate.get("creditEnabled")))throw fail("CREGIS_SWITCH_UNCHANGED");
            if(db.createNetworkSwitchCase(config.getProjectId(),version,network,networkVersion,on(current.get("depositEnabled"))?1:0,
                    deposit?1:0,on(gate.get("payoutEnabled"))?1:0,reason.trim(),actor)!=1)throw new IllegalStateException("CREGIS_SWITCH_CASE_INSERT_FAILED");
            Long id=db.lastInsertId();if(id==null)throw new IllegalStateException("CREGIS_SWITCH_CASE_MISSING");
            audit.recordRequired(entry("CREGIS_NETWORK_SWITCH_PROPOSED",id,actor,Map.of("network",network.network(),"deposit",deposit,
                    "beforeDeposit",on(current.get("depositEnabled")),"expectedVersion",version,"expectedNetworkVersion",networkVersion,"reason",reason.trim())));
            return Map.of("caseId",id,"status","MAKER_DONE","network",network.network());
        });
    }
    public Map<String,Object> proposePayout(long actor,long version,boolean payout,String reason) {
        require(actor,reason);
        if(config.getMode()!=CregisProperties.Mode.PROVIDER || version<0)throw fail("CREGIS_SWITCH_CONFIG_INVALID");
        return new TransactionTemplate(txManager).execute(ignored->{
            var gate=db.lockProvisionGate();
            if(gate==null || number(gate,"version")!=version)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
            if(payout==on(gate.get("payoutEnabled")) && "IDLE".equals(gate.get("state")))throw fail("CREGIS_SWITCH_UNCHANGED");
            if(db.createSwitchCase(config.getProjectId(),version,on(gate.get("assignEnabled"))?1:0,on(gate.get("creditEnabled"))?1:0,
                    payout?1:0,reason.trim(),actor)!=1)throw new IllegalStateException("CREGIS_SWITCH_CASE_INSERT_FAILED");
            Long id=db.lastInsertId();if(id==null)throw new IllegalStateException("CREGIS_SWITCH_CASE_MISSING");
            audit.recordRequired(entry("CREGIS_PAYOUT_SWITCH_PROPOSED",id,actor,Map.of("payout",payout,"expectedVersion",version,"reason",reason.trim())));
            return Map.of("caseId",id,"status","MAKER_DONE","scope","GLOBAL_PAYOUT");
        });
    }
    public Map<String,Object> check(long actor,long caseId,String decision,String reason) {
        require(actor,reason);
        if(caseId<=0 || !Set.of("APPROVE","REJECT").contains(decision))throw new BizException(400,"CREGIS_SWITCH_REQUEST_INVALID");
        return new TransactionTemplate(txManager).execute(ignored->{
            var proposal=db.lockSwitchCase(caseId);
            if(proposal==null || number(proposal,"projectId")!=config.getProjectId() || !"MAKER_DONE".equals(proposal.get("status")))throw fail("CREGIS_SWITCH_CASE_CHANGED");
            long maker=number(proposal,"makerId");
            if(maker==actor)throw new BizException(403,"CREGIS_SWITCH_DIFFERENT_CHECKER_REQUIRED");
            if("APPROVE".equals(decision)) {
                var account=admins.selectActiveForUpdate(maker);
                if(account==null || !Integer.valueOf(1).equals(account.getStatus()) || !Integer.valueOf(0).equals(account.getIsDeleted()))
                    throw new BizException(403,"CREGIS_SWITCH_MAKER_INACTIVE");
                if(!permissions.getPermissionCodes(maker).contains("finance_d1_channel_manage"))throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");
                var gate=db.lockProvisionGate();
                if(gate==null || number(gate,"version")!=number(proposal,"expectedVersion") || "BUSY".equals(gate.get("state")))throw fail("CREGIS_SWITCH_VERSION_CHANGED");
                if(proposal.get("network")!=null)applyNetwork(proposal,gate,reason.trim(),actor,caseId);
                else {
                    if(on(proposal.get("assignEnabled"))!=on(gate.get("assignEnabled")) || on(proposal.get("creditEnabled"))!=on(gate.get("creditEnabled")))
                        throw new BizException(422,"CREGIS_SWITCH_NETWORK_REQUIRED");
                    boolean target=on(proposal.get("payoutEnabled")),restore=!"IDLE".equals(gate.get("state"));
                    if(target && (!on(gate.get("payoutEnabled")) || restore)) {
                        if(!payouts.configured() || payouts.recoveryReason()!=null)throw fail("CREGIS_SWITCH_RECOVERY_NOT_SAFE");
                        db.resolveManualEmergencyAlerts(config.getProjectId());
                    }
                    if(db.setSwitches(!restore&&on(gate.get("assignEnabled"))?1:0,!restore&&on(gate.get("creditEnabled"))?1:0,
                            target?1:0,reason.trim(),number(gate,"version"))!=1)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
                }
            }
            String status="APPROVE".equals(decision)?"RESOLVED":"REJECTED";
            if(db.checkSwitchCase(caseId,config.getProjectId(),actor,status)!=1)throw fail("CREGIS_SWITCH_CASE_CHANGED");
            var detail=new LinkedHashMap<String,Object>();detail.put("reason",reason.trim());detail.put("network",proposal.getOrDefault("network","GLOBAL_PAYOUT"));
            detail.put("deposit",proposal.get("creditEnabled"));detail.put("beforeDeposit",proposal.get("beforeDepositEnabled"));detail.put("payout",proposal.get("payoutEnabled"));
            audit.recordRequired(entry("CREGIS_SWITCH_"+decision,caseId,actor,detail));
            return Map.of("caseId",caseId,"status",status);
        });
    }
    private void applyNetwork(Map<String,Object> proposal,Map<String,Object> gate,String reason,long actor,long caseId) {
        var network=CregisNetwork.fromNetwork((String)proposal.get("network"));var current=db.lockNetworkSwitch(network);
        long version=number(proposal,"expectedNetworkVersion");
        if(current==null || number(current,"version")!=version)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
        boolean deposit=on(proposal.get("creditEnabled")),restore=!"IDLE".equals(gate.get("state"));
        boolean opens=deposit && (!on(current.get("depositEnabled")) || restore || !on(gate.get("assignEnabled")) || !on(gate.get("creditEnabled")));
        if(opens && (!config.isDepositEnabled() || !config.isDepositCreditEnabled() || !config.depositNetworkEnabled(network)
                || !config.networkConfigured(network) || payouts.recoveryReason(network)!=null))throw fail("CREGIS_SWITCH_RECOVERY_NOT_SAFE");
        if(opens && (restore || !on(gate.get("assignEnabled")) || !on(gate.get("creditEnabled")))) {
            var before=new LinkedHashMap<String,Object>();for(var peer:CregisNetwork.values())if(peer!=network)before.put(peer.network(),db.lockNetworkSwitch(peer));
            db.keepOtherNetworksPaused(network);db.resolveManualEmergencyAlerts(config.getProjectId());
            // A pay-in recovery never reopens the outbound ceiling.
            if(db.setSwitches(1,1,!restore&&on(gate.get("payoutEnabled"))?1:0,reason,number(gate,"version"))!=1)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
            var after=new LinkedHashMap<String,Object>();for(var peer:CregisNetwork.values())if(peer!=network)after.put(peer.network(),db.lockNetworkSwitch(peer));
            audit.recordRequired(entry("CREGIS_SWITCH_SHARED_RECOVERY",caseId,actor,Map.of("network",network.network(),"otherNetworksBefore",before,"otherNetworksAfter",after,"reason",reason)));
        }
        if(db.setNetworkSwitch(network,deposit?1:0,version)!=1)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
    }
    public Map<String,Object> emergencyOff(long actor,long version,String reason) {
        require(actor,reason);if(reason.trim().length()>245)throw new BizException(400,"CREGIS_SWITCH_REASON_TOO_LONG");
        return new TransactionTemplate(txManager).execute(ignored->{
            var gate=db.lockProvisionGate();if(gate==null || number(gate,"version")!=version)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
            if(db.forceEmergencyOff("EMERGENCY:"+reason.trim(),version)!=1)throw fail("CREGIS_SWITCH_VERSION_CHANGED");
            if(db.emergencyOffNetworks()!=CregisNetwork.values().length)throw new IllegalStateException("CREGIS_NETWORK_SWITCH_MISSING");
            db.insertRiskAlert(config.getProjectId(),"manual-emergency-"+version,"P0","MANUAL_EMERGENCY_OFF","admin="+actor+",reason="+reason.trim());
            audit.recordRequired(entry("CREGIS_SWITCH_EMERGENCY_OFF",0,actor,Map.of("reason",reason.trim(),"expectedVersion",version)));
            return Map.of("state","BLOCKED","assignEnabled",false,"creditEnabled",false,"payoutEnabled",false,"version",version+1);
        });
    }
    public void auditRejected(long actor,String action,String reason) {
        audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder().action("CREGIS_SWITCH_REJECTED").resourceType("CREGIS_SWITCH").resourceId("0")
                .actorType("ADMIN").actorUsername(AdminActorResolver.resolve("admin:"+actor)).result("REJECTED").riskLevel("CRITICAL")
                .detail(Map.of("action",action,"reason",reason)).build());
    }
    private static AuditLogWriteRequest entry(String action,long id,long actor,Map<String,Object> detail) {
        return AuditLogWriteRequest.builder().action(action).resourceType("CREGIS_SWITCH").resourceId(Long.toString(id)).actorType("ADMIN")
                .actorUsername(AdminActorResolver.resolve("admin:"+actor)).result("SUCCESS").riskLevel("CRITICAL").detail(detail).build();
    }
    private static void require(long actor,String reason) {
        if(actor<=0)throw new BizException(401,"ADMIN_AUTH_REQUIRED");
        if(reason==null || reason.trim().length()<10 || reason.trim().length()>255)throw new BizException(400,"CREGIS_SWITCH_REASON_REQUIRED");
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static boolean on(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
    private static BizException fail(String reason){return new BizException(409,reason);}
}
