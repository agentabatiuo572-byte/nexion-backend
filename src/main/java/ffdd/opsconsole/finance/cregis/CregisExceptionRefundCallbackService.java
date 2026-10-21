package ffdd.opsconsole.finance.cregis;

import static ffdd.opsconsole.finance.cregis.CregisExceptionRefundService.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionRefundMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class CregisExceptionRefundCallbackService {
    private final CregisExceptionRefundMapper db;
    private final CregisProperties config;
    private final CregisSigner signer;
    private final CregisDepositMapper deposits;
    private final CregisExceptionRefundService refunds;

    /** A durable signal only. The worker independently queries and proves the chain before completion. */
    @Transactional(rollbackFor=Exception.class)
    public boolean receive(Map<String,Object> callback,String peer) {
        try {
            if(config.getMode()!=CregisProperties.Mode.PROVIDER||!config.allowsCallbackSourceIp(peer)||callback==null
                    ||!signer.verify(config.getApiKey(),callback,text(callback,"sign")))return false;
            long project=integer(callback.get("pid")),cid=integer(callback.get("cid")),timestamp=integer(callback.get("timestamp"));
            long now=Instant.now().toEpochMilli();
            if(project!=config.getProjectId()||cid<=0||timestamp<now-300_000||timestamp>now+300_000
                    ||!CregisConstants.BSC_CHAIN_ID.equals(text(callback,"chain_id"))
                    ||!CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(text(callback,"token_id"))
                    ||!text(callback,"nonce").matches("[A-Za-z0-9]{6}"))return false;
            String currency=text(callback,"currency");
            if(!("USDT".equalsIgnoreCase(currency)||"USDT-BEP20".equalsIgnoreCase(currency)
                    ||CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(currency)))return false;
            if(deposits.lockProvisionGate()==null)throw new IllegalStateException("CREGIS_RISK_GATE_MISSING");
            var row=db.byKey(project,text(callback,"third_party_id"));if(row==null||!text(row,"businessKey").startsWith("CR-RF-"))return false;
            if(row.get("dispatchIntentAt")==null)return false;
            if(!text(row,"returnAddress").equalsIgnoreCase(text(callback,"address"))
                    ||!(callback.get("amount") instanceof String amount)||CregisAmount.parsePositive(amount)==null
                    ||decimal(row,"netAmount").compareTo(new BigDecimal(amount))!=0
                    ||row.get("providerCid")!=null&&number(row,"providerCid")!=cid)return false;
            int status=Math.toIntExact(integer(callback.get("status")));CregisGateway.PayoutStatus.fromProviderCode(status);
            String txid=text(callback,"txid");if(txid.isBlank())txid=null;
            if(txid!=null&&!txid.matches("(?i)(0x)?[0-9a-f]{64}")||status==6&&txid==null)return false;
            String hash=sha(text(row,"caseNo")+"|"+cid+"|"+status+"|"+txid);
            db.callback(hash,text(row,"caseNo"),cid,status,txid);
            if(!java.util.Set.of(2,3,4).contains(status)||txid!=null)refunds.lateCallback(text(row,"caseNo"));
            return true;
        } catch(IllegalArgumentException|ArithmeticException|CregisGatewayException malformed){return false;}
    }
    private long integer(Object value) {
        if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("CREGIS_REFUND_CALLBACK_INVALID");
        return ((Number)value).longValue();
    }
}
