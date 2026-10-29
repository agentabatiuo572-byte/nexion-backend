package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;

/** Fixed typed schema. No polymorphic typing or authenticated HTTP responses. */
final class SupportAnalyticsCacheCodec {
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private record Generation(int schemaVersion,SupportAnalyticsFactSnapshot statistics) { }
    String encode(SupportAnalyticsFactSnapshot snapshot) {
        try {validate(snapshot);return json.writeValueAsString(new Generation(1,snapshot));}
        catch(java.io.IOException|RuntimeException failure){throw unavailable();}
    }
    SupportAnalyticsFactSnapshot decode(String value) {
        try {
            var generation=json.readValue(value,Generation.class);
            if(generation==null||generation.schemaVersion()!=1||generation.statistics()==null)throw unavailable();
            validate(generation.statistics());
            if(generation.statistics().asOf().isAfter(java.time.Instant.now().plusSeconds(5)))throw unavailable();
            return generation.statistics();
        }catch(java.io.IOException|RuntimeException failure){throw unavailable();}
    }
    private static void validate(SupportAnalyticsFactSnapshot snapshot) {
        var c=required(required(snapshot).compiled());
        if(!c.customers().keySet().equals(java.util.Set.of("HISTORY:*","HISTORY:USDT","HISTORY:UVT","ASSET:*","ASSET:USDT","ASSET:UVT")))throw new IllegalArgumentException();
        required(c.devices());required(c.activityWindow().status());required(c.rules());
        var f=required(c.funds());required(f.wallets().state());required(f.withdrawals().state());
        f.wallets().rows().forEach(w->required(w.state()));f.withdrawals().rows().forEach(w->required(w.state()));
        c.activities().values().forEach(SupportAnalyticsCacheCodec::activity);
        c.invitations().values().forEach(i->required(i.completeness()));
        c.verifiedNet().values().forEach(n->{required(n.customers());required(n.origins());required(n.attributions());required(n.gaps());n.customers().values().forEach(v->required(v.state()));});
        java.util.Set<Long> ids=null;
        for(var view:c.customers().values()) {
            var current=new java.util.HashSet<Long>();
            for(var customer:view) {
                if(customer.customerId()<=0 || !snapshot.subjects().contains(customer.customerId()) || !current.add(customer.customerId()))throw new IllegalArgumentException();
                required(customer.category());required(customer.placement());required(customer.owner());
                required(customer.first().status());required(customer.first().state());
                var m=required(customer.metrics());required(m.lifetimeBasis());required(m.lifetimeStatus());
                m.lifetime().forEach(t->{money(t.deposits());money(t.purchases());money(t.net());});
                var i=required(m.invitations());required(i.status());count(i.directCustomers());count(i.descendantCustomers());i.descendantDeposits().forEach(t->money(t.deposits()));
                var d=required(m.devices());required(d.status());count(d.held());count(d.unknownHolding());d.partitions().forEach(p->count(p.devices()));activity(m.activity());
            }
            if(ids!=null&&!ids.equals(current))throw new IllegalArgumentException();ids=current;
        }
    }
    private static void activity(CustomerActivity a){required(required(a).state());required(a.status());}
    private static void count(Count c){required(required(c).status());}
    private static void money(Money m){required(required(m).status());}
    private static <T> T required(T value){if(value==null)throw new IllegalArgumentException();return value;}
    private static BizException unavailable(){return new BizException(503,"SUPPORT_ANALYTICS_CACHE_INVALID");}
}
