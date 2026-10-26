package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor
public class CregisAddressPoolPolicyService {
    private final CregisDepositMapper db;
    private final CregisExceptionAdminGuard admins;
    private final AuditLogService audit;
    private final PlatformTransactionManager txManager;
    public CregisAddressPoolPolicy read() {admins.require(false);return CregisAddressPoolPolicy.from(db.poolPolicy());}
    public CregisAddressPoolPolicy save(CregisAddressPoolPolicy policy,String reason) {
        long actor=admins.require(true);policy.validate();
        if(reason==null || reason.trim().length()<10 || reason.trim().length()>300)
            throw new BizException(400,"CREGIS_POOL_POLICY_REASON_REQUIRED");
        return new TransactionTemplate(txManager).execute(ignored->{
            db.lockProvisionGate();
            if(db.updatePoolPolicy(policy.enabled(),policy.target(),policy.lowWatermark(),policy.batchSize(),
                    policy.concurrency(),policy.version())!=1) throw new BizException(409,"CREGIS_POOL_POLICY_VERSION_CONFLICT");
            audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_ADDRESS_POOL_POLICY_UPDATE")
                    .resourceType("CREGIS_ADDRESS_POOL").resourceId("1").actorId(actor).actorType("ADMIN")
                    .actorUsername("admin:"+actor).riskLevel("MEDIUM").result("SUCCESS")
                    .detail(Map.of("policy",policy,"reason",reason.trim())).build());
            return CregisAddressPoolPolicy.from(db.poolPolicy());
        });
    }
}
