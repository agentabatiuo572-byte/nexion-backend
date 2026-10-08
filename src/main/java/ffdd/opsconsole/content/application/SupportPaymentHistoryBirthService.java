package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.SupportPaymentHistoryBirthMapper;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the existing new-account registration facade calls this lifetime boundary. */
@ApplicationService
@RequiredArgsConstructor
public class SupportPaymentHistoryBirthService {
    public static final String CAPTURE_PROTOCOL="support-payment-attribution-v1";
    private final SupportPaymentHistoryBirthMapper mapper;
    private final DataSource dataSource;

    @Transactional(propagation=Propagation.MANDATORY)
    public void registerNewAccount(long customer) {
        if(customer<=0 || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || TransactionSynchronizationManager.getResource(dataSource)==null)
            throw new IllegalStateException("New-account payment history requires the registration transaction");
        var row=mapper.customer(customer);
        if(row==null || !Long.valueOf(customer).equals(row.id()) || !Integer.valueOf(0).equals(row.isDeleted()))
            throw new IllegalStateException("New-account payment history customer is missing");
        // A replay preserves the original environment and boundary, including excluded accounts.
        if(mapper.find(customer)!=null) return;
        String environment=row.sandbox()==null?"UNKNOWN":row.sandbox()==0?"PRODUCTION":"EXCLUDED";
        if(mapper.insert(customer,CAPTURE_PROTOCOL,row.sandbox(),environment)!=1)
            throw new IllegalStateException("New-account payment history was not inserted");
    }
}
