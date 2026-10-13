package ffdd.opsconsole.auth.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper;
import ffdd.opsconsole.auth.mapper.AuthRegistrationBirthMapper.Customer;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.AuthenticatedRegistrationFacts;
import ffdd.opsconsole.finance.facade.SupportFundAuthBirthFacade.Outcome;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade;
import ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual AUTH INSERT authority, not an account-ID-based history backfill API. */
@ApplicationService
public class AuthNewAccountCreator {
    private final UserRegistrationWriteFacade users;
    private final AuthRegistrationBirthMapper mapper;
    private final SupportFundAuthBirthFacade funds;
    private final DataSource dataSource;
    private final ObjectMapper json;

    public AuthNewAccountCreator(UserRegistrationWriteFacade users, AuthRegistrationBirthMapper mapper,
            SupportFundAuthBirthFacade funds, DataSource dataSource, ObjectMapper json) {
        this.users=users; this.mapper=mapper; this.funds=funds; this.dataSource=dataSource;
        this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public interface BirthHandle { long customerId(); }

    @Transactional(propagation=Propagation.MANDATORY)
    public BirthHandle insertNew(NewAccountCommand command) {
        Physical tx=transaction();
        long id=users.insertNewAccount(command);
        Customer row=mapper.customer(id);
        if (!valid(row,id) || command.sandbox()!=row.sandbox()) throw failure("AUTH_BIRTH_INSERT_IDENTITY_MISMATCH");
        Token token=new Token(this,tx,row,command.sandbox()==0?UUID.randomUUID().toString():null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { token.active.set(false); }
        });
        return token;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void ensureWallet(BirthHandle handle) {
        Token token=token(handle,false);
        if (!token.walletWritten.compareAndSet(false,true)) throw failure("AUTH_BIRTH_WALLET_ALREADY_ENSURED");
        users.ensureRegisteredWallet(token.customerId(),token.customer.sandbox(),token.incarnation);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Outcome complete(BirthHandle handle,String eventId) {
        Token token=token(handle,true);
        if (!token.walletWritten.get()) throw failure("AUTH_BIRTH_WALLET_NOT_ENSURED");
        if (token.customer.sandbox()!=0) return Outcome.EXCLUDED;
        if (eventId==null || !eventId.matches("[0-9a-f]{32}")) throw failure("AUTH_BIRTH_EVENT_ID_INVALID");
        Customer current=mapper.customer(token.customerId());
        if (!token.customer.equals(current)) throw failure("AUTH_BIRTH_CUSTOMER_CHANGED");
        var event=mapper.event(eventId);
        if (event==null || event.id()==null || event.id()<=0 || !eventId.equals(event.eventId())
                || !"USER_REGISTRATION".equals(event.aggregateType()) || !String.valueOf(token.customerId()).equals(event.aggregateId())
                || !"auth.register_completed".equals(event.eventType()) || !"auth.register_completed".equals(event.eventName())
                || event.eventTs()==null || !Boolean.TRUE.equals(event.serverAuthoritative())
                || !Boolean.TRUE.equals(event.schemaRegistered()) || !Boolean.TRUE.equals(event.analyticsEvent())
                || event.schemaRevision()==null || event.schemaRevision()<=0 || !Integer.valueOf(0).equals(event.isDeleted()))
            throw failure("AUTH_BIRTH_EVENT_MISMATCH");
        try {
            JsonNode p=json.readTree(event.payload());
            if (p==null || !p.isObject() || !exactId(p.get("userId"),token.customerId())
                    || !exactId(p.get("user_id"),token.customerId()) || !eventId.equals(p.path("event_id").asText())
                    || !"auth.register_completed".equals(p.path("event_name").asText())
                    || !p.path("is_server_authoritative").isBoolean() || !p.path("is_server_authoritative").booleanValue()
                    || !exactId(p.get("schema_revision"),event.schemaRevision())) throw failure("AUTH_BIRTH_PAYLOAD_MISMATCH");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("AUTH_BIRTH_PAYLOAD_INVALID",ex); }
        return funds.record(new AuthenticatedRegistrationFacts(token.customerId(),current.createdAt(),token.incarnation,
            eventId,event.id(),event.eventTs(),event.schemaRevision()));
    }

    private static boolean exactId(JsonNode node,long value) { return node!=null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue()==value; }
    private static boolean valid(Customer c,long id) { return c!=null && Long.valueOf(id).equals(c.id()) && c.createdAt()!=null
        && (Integer.valueOf(0).equals(c.sandbox()) || Integer.valueOf(1).equals(c.sandbox()))
        && Integer.valueOf(0).equals(c.isDeleted()) && "ACTIVE".equals(c.status()); }
    private Token token(BirthHandle handle,boolean consume) {
        Physical current=transaction();
        if (!(handle instanceof Token t) || t.owner!=this || t.thread!=Thread.currentThread() || !t.active.get() || t.used.get()
                || t.tx.holder()!=current.holder() || t.tx.connection()!=current.connection()
                || consume && !t.used.compareAndSet(false,true)) throw failure("AUTH_BIRTH_TOKEN_INVALID");
        return t;
    }
    private Physical transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw failure("AUTH_BIRTH_WRITABLE_TRANSACTION_REQUIRED");
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder h) || !h.isSynchronizedWithTransaction()) throw failure("AUTH_BIRTH_PHYSICAL_TRANSACTION_REQUIRED");
        try {
            Connection c=h.getConnection();
            if (c.isClosed() || c.isReadOnly() || c.getAutoCommit()) throw failure("AUTH_BIRTH_PHYSICAL_TRANSACTION_REQUIRED");
            return new Physical(h,c);
        } catch (SQLException ex) { throw new IllegalStateException("AUTH_BIRTH_CONNECTION_FAILED",ex); }
    }
    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    private record Physical(ConnectionHolder holder,Connection connection) {}
    private static final class Token implements BirthHandle {
        private final AuthNewAccountCreator owner;
        private final Physical tx;
        private final Thread thread=Thread.currentThread();
        private final Customer customer;
        private final String incarnation;
        private final AtomicBoolean active=new AtomicBoolean(true),used=new AtomicBoolean(),walletWritten=new AtomicBoolean();
        private Token(AuthNewAccountCreator owner,Physical tx,Customer customer,String incarnation) {
            this.owner=owner;this.tx=tx;this.customer=customer;this.incarnation=incarnation;
        }
        @Override public long customerId() { return customer.id(); }
    }
}
