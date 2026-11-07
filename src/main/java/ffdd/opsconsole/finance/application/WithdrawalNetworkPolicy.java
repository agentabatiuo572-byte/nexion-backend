package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.*;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
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

    public List<Map<String,Object>> statuses() {
        var rows=new ArrayList<Map<String,Object>>();
        for(String network:List.of("TRC20","BEP20","ERC20")) {
            String value=config.activeValue("withdrawal."+network.toLowerCase(Locale.ROOT)+".enabled").orElse("").trim().toLowerCase(Locale.ROOT);
            if(!Set.of("true","false","1","0","on","off").contains(value))throw new BizException(503,"D5_NETWORK_CONFIG_UNAVAILABLE");
            boolean configured=Set.of("true","1","on").contains(value);
            String reason=!configured?"NETWORK_SWITCH_OFF"
                    :cregis.getMode()==CregisProperties.Mode.DISABLED?"NOT_CONFIGURED"
                    :cregis.getMode()==CregisProperties.Mode.PROVIDER && "TRC20".equals(network)?"NETWORK_UNSUPPORTED":null;
            if(reason==null && cregis.getMode()==CregisProperties.Mode.PROVIDER)
                reason=(String)payouts.capability(CregisNetwork.fromNetwork(network)).get("disabledReason");
            var row=new LinkedHashMap<String,Object>();row.put("network","USDT-"+network);row.put("configured",configured);
            row.put("enabled",reason==null);row.put("disabledReason",reason);rows.add(row);
        }
        return rows;
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
        String value=db.lockWithdrawalNetworkPermission("withdrawal."+chain.substring(5).toLowerCase(Locale.ROOT)+".enabled");
        if(value==null || !Set.of("true","1","on").contains(value.trim().toLowerCase(Locale.ROOT)) || !enabledNetworks().contains(chain))
            throw new BizException(409,"WITHDRAWAL_NETWORK_DISABLED");
    }
}
