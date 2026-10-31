package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositPolicyMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class CregisDepositPolicyService {
    private final CregisDepositPolicyMapper db;
    private final AuditLogService audit;
    private final CregisProperties config;
    public CregisDepositPolicy current() {
        var policy = db.current();
        if (policy == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_MISSING");
        return effective(policy);
    }
    @Transactional(rollbackFor=Exception.class)
    public CregisDepositPolicy discover(long projectId, String txid) {
        if (projectId <= 0) throw new BizException(422,"CREGIS_DEPOSIT_POLICY_IDENTITY_INVALID");
        String key=normalize(txid);
        if (db.lockPolicy() == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_LOCK_MISSING");
        var locked=db.lockCurrent();
        if (locked == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_MISSING");
        db.insertSnapshot(projectId,key,locked.version(),effective(locked).confirmations());
        var snapshot=db.lockSnapshot(projectId,key);
        if (snapshot == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_SNAPSHOT_MISSING");
        return effective(snapshot);
    }
    public CregisDepositPolicy snapshot(long projectId, String txid) {
        var snapshot=db.snapshot(projectId,normalize(txid));
        if (snapshot == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_SNAPSHOT_MISSING");
        return effective(snapshot);
    }
    public boolean integerPrincipalRequired(long projectId,String txid) {
        return db.integerPrincipalRequired(projectId,normalize(txid));
    }
    private static String normalize(String txid) {
        if (txid == null || !txid.matches("(?i)(0x)?[0-9a-f]{64}")) throw new BizException(422,"CREGIS_DEPOSIT_POLICY_IDENTITY_INVALID");
        String key=txid.toLowerCase(java.util.Locale.ROOT);
        return key.startsWith("0x") ? key : "0x"+key;
    }
    private CregisDepositPolicy effective(CregisDepositPolicy policy) {
        if (config.getDepositConfirmations() < 15) throw new BizException(503,"CREGIS_CONFIRMATIONS_INVALID");
        return new CregisDepositPolicy(policy.version(),policy.minDepositUsdt(),policy.feeUsdt(),Math.max(policy.confirmations(),config.getDepositConfirmations()));
    }
    @Transactional(rollbackFor=Exception.class)
    public CregisDepositPolicy publish(Map<String,Object> params, String operator) {
        if (!A2ReplayContext.isReplaying() || A2ReplayContext.operationId() == null) throw new BizException(409,"A2_CONFIRMATION_REQUIRED");
        var requested = CregisDepositPolicy.parse(params);
        if (config.getDepositConfirmations() < 15 || requested.confirmations() < config.getDepositConfirmations()) throw new BizException(422,"CREGIS_DEPOSIT_CONFIRMATIONS_BELOW_SAFETY_FLOOR");
        if (db.lockPolicy() == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_LOCK_MISSING");
        var before = db.lockCurrent();
        if (before == null) throw new BizException(503,"CREGIS_DEPOSIT_POLICY_MISSING");
        if (before.version() != requested.version()) throw new BizException(409,"CREGIS_DEPOSIT_POLICY_VERSION_CONFLICT");
        var after = new CregisDepositPolicy(before.version()+1,requested.minDepositUsdt(),requested.feeUsdt(),requested.confirmations());
        if (db.insertPolicy(after,A2ReplayContext.operationId(),String.valueOf(params.get("reason")).trim()) != 1) throw new IllegalStateException("CREGIS_DEPOSIT_POLICY_INSERT_FAILED");
        audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_DEPOSIT_POLICY_UPDATE").resourceType("CREGIS_DEPOSIT_POLICY")
                .resourceId("current").actorType("ADMIN").actorUsername(operator).riskLevel("HIGH").result("SUCCESS")
                .detail(Map.of("before",before,"after",after,"reason",params.get("reason"),"operationId",A2ReplayContext.operationId())).build());
        return after;
    }
}
