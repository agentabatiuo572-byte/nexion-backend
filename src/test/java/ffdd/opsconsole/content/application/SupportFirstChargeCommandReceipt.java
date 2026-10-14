package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the independently committed claims of these exact, pre-recorded production commands. */
final class SupportFirstChargeCommandReceipt {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<Long,Map<String,Object>> creators;
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final Map<String,Intent> commands=new LinkedHashMap<>();
    private final List<Map<String,Object>> evidence=new ArrayList<>();

    SupportFirstChargeCommandReceipt(JdbcTemplate jdbc,ObjectMapper json,Map<Long,Map<String,Object>> creators,org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.json=json;this.creators=Map.copyOf(creators);this.transactions=transactions;
    }
    private static final class Intent {
        final long customer;final String scope,key,hash;final Object request;
        Long rowId;boolean cleaned;
        Intent(long c,String s,String k,String h,Object r){customer=c;scope=s;key=k;hash=h;request=r;}
    }
    <T>T invoke(long customer,String scope,String key,String hash,Object request,Supplier<T> command) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(creators).containsKey(customer);
        assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",customer)).isEqualTo(creators.get(customer));
        assertThat(scope).matches("APP:ORDER_(CREATE|PAYMENT):USER:[0-9]+|E4_ORDER_REFUND");
        if(scope.startsWith("APP:"))assertThat(scope).endsWith(":USER:"+customer);
        if(!scope.startsWith("APP:ORDER_CREATE:")) {
            assertThat(request).isInstanceOf(Map.class);
            Object orderNo=((Map<?,?>)request).get("orderNo");assertThat(orderNo).isInstanceOf(String.class);
            var order=jdbc.queryForMap("SELECT * FROM nx_order WHERE order_no=? AND user_id=? AND is_deleted=0",orderNo,customer);
            assertThat(order.get("order_no")).isEqualTo(orderNo);assertThat(((Number)order.get("user_id")).longValue()).isEqualTo(customer);
            evidence.add(Map.of("event","REAL_OWNED_ORDER_BEFORE_COMMAND","order",new LinkedHashMap<>(order),"customerCreator",creators.get(customer)));
        }
        assertThat(key).startsWith("fst13-");assertThat(hash).matches("[0-9a-f]{64}");
        String identity=scope+"\n"+key;Intent intent=commands.get(identity);
        if(intent==null) {
            assertThat(absentBeforeCommand(scope,key)).as("New exact command was absent before its production invocation").isEmpty();
            intent=new Intent(customer,scope,key,hash,request);commands.put(identity,intent);
            evidence.add(Map.of("event","PREPARED_BEFORE_PRODUCTION_CALL","scope",scope,"key",key,"requestHash",hash,"request",request,"customerCreator",creators.get(customer)));
        }else {assertThat(intent.hash).isEqualTo(hash);assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(intent.request)).isEqualTo(json.valueToTree(request));}
        Throwable failed=null;
        try{return command.get();}
        catch(RuntimeException|Error ex){failed=ex;throw ex;}
        finally {
            try {
                var current=rows(scope,key);
                if(!current.isEmpty()) {
                    assertThat(current).hasSize(1);var row=current.get(0);assertThat(row.get("request_hash")).isEqualTo(hash);
                    long id=((Number)row.get("id")).longValue();assertThat(id).isPositive();
                    if(intent.rowId==null)intent.rowId=id;else assertThat(id).isEqualTo(intent.rowId);
                    evidence.add(Map.of("event","PRODUCTION_CALL_FINALLY_ROW_READBACK","scope",scope,"key",key,"row",new LinkedHashMap<>(row)));
                }
            }catch(RuntimeException|Error capture){if(failed!=null)failed.addSuppressed(capture);else throw capture;}
        }
    }
    private List<Map<String,Object>> absentBeforeCommand(String scope,String key) {
        // Committed nonlocking read: an absent FOR UPDATE in the outer RR transaction would gap-lock the independent claim insertion.
        var read=new org.springframework.transaction.support.TransactionTemplate(transactions);
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);
        return Objects.requireNonNull(read.execute(s->jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,key)));
    }
    private List<Map<String,Object>> rows(String scope,String key) {
        // FOR UPDATE is a current read: an earlier RR snapshot cannot hide the REQUIRES_NEW claim.
        return jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=? FOR UPDATE",scope,key);
    }
    void cleanupAfterRollback() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var cleanup=new ArrayList<Runnable>();
        for(Intent intent:commands.values())cleanup.add(()->{
            assertThat(jdbc.queryForMap("SELECT id,referral_code,nickname,created_at FROM nx_user WHERE id=?",intent.customer)).isEqualTo(creators.get(intent.customer));
            var current=rows(intent.scope,intent.key);
            if(intent.rowId==null) {assertThat(current).as("No captured claim means no authority to remove a row").isEmpty();return;}
            assertThat(current).hasSize(1);var row=current.get(0);
            assertThat(((Number)row.get("id")).longValue()).isEqualTo(intent.rowId);
            assertThat(row.get("scope")).isEqualTo(intent.scope);assertThat(row.get("idempotency_key")).isEqualTo(intent.key);assertThat(row.get("request_hash")).isEqualTo(intent.hash);
            assertThat(row.get("status")).as("Actual outer rollback must finalize this independently committed claim").isEqualTo("FAILED");
            assertThat(row.get("response_json")).isNull();
            var fence=fullRowFence(row);
            assertThat(jdbc.update("DELETE FROM nx_admin_idempotency_record WHERE "+fence.sql(),fence.arguments().toArray())).isEqualTo(1);
            assertThat(rows(intent.scope,intent.key)).isEmpty();intent.cleaned=true;
            evidence.add(Map.of("event","EXACT_PK_AND_ALL_COLUMN_NULLSAFE_CLEANUP","scope",intent.scope,"key",intent.key,"finalRow",new LinkedHashMap<>(row),"customerCreator",creators.get(intent.customer)));
        });
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }
    record Fence(String sql,List<Object> arguments) { }
    static Fence fullRowFence(Map<String,Object> row) {
        assertThat(row).containsKeys("id","scope","idempotency_key","request_hash","status");
        assertThat(row.get("id")).isInstanceOf(Number.class);assertThat(((Number)row.get("id")).longValue()).isPositive();
        var predicates=new ArrayList<String>();var arguments=new ArrayList<Object>();
        row.forEach((column,value)->{assertThat(column).matches("[a-zA-Z0-9_]+");predicates.add("`"+column+"` <=> ?");arguments.add(value);});
        return new Fence(String.join(" AND ",predicates),Collections.unmodifiableList(arguments));
    }
    List<Map<String,Object>> receipts(){return List.copyOf(evidence);}
}
