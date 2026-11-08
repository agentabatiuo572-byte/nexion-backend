package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.mapper.AppWithdrawalMapper;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Shared effective network facts for App, payout-address writes and D5. Reads never dispatch. */
@Service @RequiredArgsConstructor
public class WithdrawalNetworkPolicy {
    private final PlatformConfigFacade config;
    private final CregisProperties cregis;
    private final CregisPayoutControl payouts;
    private final CregisDepositMapper db;
    private final AppWithdrawalMapper withdrawals;

    public List<Map<String,Object>> statuses() {
        var rows=new ArrayList<Map<String,Object>>();
        String gateValue = Optional.ofNullable(withdrawals.emergencyValue("killswitch.withdraw"))
                .or(() -> Optional.ofNullable(withdrawals.emergencyValue("emergency.killswitch.withdraw")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).orElse(null);
        boolean gateKnown = gateValue != null && Set.of("enabled", "enable", "on", "true", "1",
                "disabled", "disable", "off", "false", "0").contains(gateValue);
        boolean gateEnabled = gateKnown && KillSwitchState.parse(gateValue);
        for(String network:List.of("TRC20","BEP20","ERC20")) {
            String value=config.activeValue("withdrawal."+network.toLowerCase(Locale.ROOT)+".enabled").orElse("").trim().toLowerCase(Locale.ROOT);
            if(!Set.of("true","false","1","0","on","off").contains(value))throw new BizException(503,"D5_NETWORK_CONFIG_UNAVAILABLE");
            boolean configured=Set.of("true","1","on").contains(value);
            String reason=!configured?"NETWORK_SWITCH_OFF"
                    :!gateKnown?"WITHDRAWAL_KILL_SWITCH_UNAVAILABLE"
                    :!gateEnabled?"WITHDRAWAL_KILL_SWITCH_DISABLED"
                    :cregis.getMode()==CregisProperties.Mode.DISABLED?"NOT_CONFIGURED"
                    :null;
            if(reason==null && cregis.getMode()==CregisProperties.Mode.PROVIDER)
                reason=(String)payouts.capability(CregisNetwork.fromNetwork(network)).get("disabledReason");
            var row=new LinkedHashMap<String,Object>();row.put("network","USDT-"+network);row.put("configured",configured);
            row.put("enabled",reason==null);row.put("disabledReason",reason);rows.add(row);
        }
        return rows;
    }
    /** Operator projection preserves uncertainty instead of labelling a failed capability read as disabled. */
    public List<Map<String,Object>> operatorStatuses(Map<String,Boolean> configuredNetworks) {
        List<Map<String,Object>> facts;
        try {
            facts = statuses();
        } catch (RuntimeException unavailable) {
            facts = new ArrayList<>();
            for (String network : List.of("TRC20", "BEP20", "ERC20")) {
                var row = new LinkedHashMap<String,Object>();
                row.put("network", "USDT-" + network);
                row.put("configured", configuredNetworks.get(network.toLowerCase(Locale.ROOT)));
                row.put("enabled", null);
                row.put("disabledReason", "NETWORK_STATUS_UNAVAILABLE");
                facts.add(row);
            }
        }
        var result = new ArrayList<Map<String,Object>>();
        for (var fact : facts) {
            var row = new LinkedHashMap<>(fact);
            String reason = (String) row.get("disabledReason");
            boolean unknown = reason != null && Set.of("PROVIDER_CAPABILITY_UNAVAILABLE",
                    "CHAIN_PROOF_UNAVAILABLE", "NETWORK_STATUS_UNAVAILABLE", "WITHDRAWAL_KILL_SWITCH_UNAVAILABLE").contains(reason);
            if (unknown) row.put("enabled", null);
            row.put("state", unknown ? "UNKNOWN" : Boolean.TRUE.equals(row.get("enabled")) ? "AVAILABLE" : "UNAVAILABLE");
            result.add(row);
        }
        return result;
    }
    public List<String> enabledNetworks() {
        return statuses().stream().filter(row->Boolean.TRUE.equals(row.get("enabled"))).map(row->(String)row.get("network")).toList();
    }
    /** Lock ordering only: closed channels must still allow a successful receipt to replay. */
    public void lockBeforeUser() {
        if(cregis.getMode()==CregisProperties.Mode.PROVIDER)db.lockProvisionGate();
    }
    public void requireEnabledForWrite(String chain) {
        if(!Set.of("USDT-TRC20","USDT-BEP20","USDT-ERC20").contains(chain))throw new BizException(409,"WITHDRAWAL_NETWORK_DISABLED");
        if(cregis.getMode()==CregisProperties.Mode.PROVIDER) {
            var gate=db.lockProvisionGate();
            if(gate==null || !"IDLE".equals(gate.get("state")) || !(Boolean.TRUE.equals(gate.get("payoutEnabled"))
                    || gate.get("payoutEnabled") instanceof Number n && n.intValue()==1))throw new BizException(409,"WITHDRAWAL_NETWORK_DISABLED");
        }
        String primary=db.lockWithdrawalEmergency("killswitch.withdraw");
        if(!ffdd.opsconsole.emergency.domain.KillSwitchState.parse(primary==null?db.lockWithdrawalEmergency("emergency.killswitch.withdraw"):primary))
            throw new BizException(409,"WITHDRAWAL_KILL_SWITCH_DISABLED");
        String value=db.lockWithdrawalNetworkPermission("withdrawal."+chain.substring(5).toLowerCase(Locale.ROOT)+".enabled");
        if(value==null || !Set.of("true","1","on").contains(value.trim().toLowerCase(Locale.ROOT)) || !enabledNetworks().contains(chain))
            throw new BizException(409,"WITHDRAWAL_NETWORK_DISABLED");
    }
}
