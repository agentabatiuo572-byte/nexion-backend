package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.util.*;

/** Fixed typed schema. No polymorphic typing or authenticated HTTP responses. */
final class SupportAnalyticsCacheCodec {
    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final String UPGRADE_REASON = "GENERATION_UPGRADING";
    private static final TypeReference<Map<Long,LegacyInvitation>> LEGACY_INVITATIONS = new TypeReference<>() { };
    private static final TypeReference<Map<String,List<LegacyCustomer>>> LEGACY_CUSTOMERS = new TypeReference<>() { };
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private record Generation(int schemaVersion,SupportAnalyticsFactSnapshot statistics) { }
    private record LegacyInvitation(long rootCustomerId,Integer rootSandbox,List<Long> directCustomerIds,
            List<Long> descendantCustomerIds,SupportInvitationReadFacade.Completeness completeness,Set<LegacyReason> reasons) {
        LegacyInvitation {
            if(completeness==null)throw new IllegalArgumentException();
            directCustomerIds=List.copyOf(directCustomerIds);
            descendantCustomerIds=List.copyOf(descendantCustomerIds);
            reasons=Set.copyOf(reasons);
        }
    }
    private record LegacyInvitationSummary(Count directCustomers,Count descendantCustomers,
            List<InvitationCurrencyTotal> descendantDeposits,Status status,List<String> reasons) {
        LegacyInvitationSummary {
            if(status==null || directCustomers==null || descendantCustomers==null
                    || directCustomers.status()==null || descendantCustomers.status()==null)throw new IllegalArgumentException();
            descendantDeposits=List.copyOf(descendantDeposits);
            reasons=List.copyOf(reasons);
        }
    }
    private record LegacyCustomerMetrics(Basis lifetimeBasis,Status lifetimeStatus,List<CustomerCurrencyTotals> lifetime,
            LegacyInvitationSummary invitations,DeviceSummary devices,CustomerActivity activity) {
        LegacyCustomerMetrics {
            if(lifetimeStatus==null || invitations==null)throw new IllegalArgumentException();
            lifetime=List.copyOf(lifetime);
        }
    }
    private record LegacyCustomer(long customerId,Category category,Placement placement,boolean handoverRequired,
            FirstSelection first,CurrentOwner owner,LegacyCustomerMetrics metrics) {
        LegacyCustomer {if(metrics==null)throw new IllegalArgumentException();}
    }
    private enum LegacyReason {
        MISSING_ROOT,UNKNOWN_ENVIRONMENT,ENVIRONMENT_CONFLICT,DELETED_NODE,DANGLING_SPONSOR,
        INVALID_ROW,DUPLICATE_EDGE,CONFLICTING_EDGE,CYCLE,SOURCE_READ_FAILED
    }
    String encode(SupportAnalyticsFactSnapshot snapshot) {
        try {validate(snapshot);return json.writeValueAsString(new Generation(CURRENT_SCHEMA_VERSION,snapshot));}
        catch(java.io.IOException|RuntimeException failure){throw unavailable();}
    }
    SupportAnalyticsFactSnapshot decode(String value) {
        try {
            JsonNode root=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if(root==null || !root.isObject())throw unavailable();
            JsonNode versionNode=root.get("schemaVersion");
            if(versionNode==null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt())throw unavailable();
            int version=versionNode.intValue();
            if(version==LEGACY_SCHEMA_VERSION)root=migrateLegacy(root);
            else if(version!=CURRENT_SCHEMA_VERSION)throw unavailable();
            var generation=json.treeToValue(root,Generation.class);
            if(generation==null||generation.statistics()==null)throw unavailable();
            validate(generation.statistics());
            if(generation.statistics().asOf().isAfter(java.time.Instant.now().plusSeconds(5)))throw unavailable();
            return generation.statistics();
        }catch(java.io.IOException|RuntimeException failure){throw unavailable();}
    }

    /**
     * Generation 1 predates the depth counters/inviter-reason field. First re-read
     * its original invitation and customer shapes under the same strict creator,
     * type and enum rules as generation 1; refusing malformed/unknown fields is
     * still CACHE_INVALID. Valid first-charge and money facts remain valid, while
     * invitation counts are no longer confirmable: replace their reasons with
     * GENERATION_UPGRADING and leave their confirmed values null. Historical v1
     * invitation FAILED is a generation limitation too, so it becomes UNKNOWN; other
     * source failures and generation-2 FAILED stay untouched. The depth fields are
     * internal placeholders that only satisfy the row invariant; UNKNOWN never turns
     * them into confirmed counts.
     */
    private JsonNode migrateLegacy(JsonNode root) throws java.io.IOException {
        if(!(root instanceof ObjectNode generation))throw unavailable();
        ObjectNode migrated=generation.deepCopy();
        JsonNode statistics=migrated.get("statistics");
        if(!(statistics instanceof ObjectNode stats))throw unavailable();
        JsonNode compiledNode=stats.get("compiled");
        if(!(compiledNode instanceof ObjectNode compiled))throw unavailable();
        JsonNode invitationsNode=compiled.get("invitations");
        if(!(invitationsNode instanceof ObjectNode invitations))throw unavailable();
        JsonNode customersNode=compiled.get("customers");
        if(!(customersNode instanceof ObjectNode))throw unavailable();
        Map<Long,LegacyInvitation> legacyInvitations=json.readerFor(LEGACY_INVITATIONS).readValue(invitationsNode);
        json.readerFor(LEGACY_CUSTOMERS).readValue(customersNode);
        for(var it=invitations.fields();it.hasNext();) {
            var entry=it.next();
            LegacyInvitation old=legacyInvitations.get(Long.valueOf(entry.getKey()));
            ObjectNode row=(ObjectNode)entry.getValue();
            int total=old.descendantCustomerIds().size();
            row.put("completeness","UNKNOWN");
            row.set("reasons",upgradeReasons());
            row.put("withinRewardRange",total);
            row.put("beyondRewardRange",0);
            row.putArray("inviterReasons");
        }
        for(var views=customersNode.fields();views.hasNext();) {
            var view=views.next();
            for(JsonNode customer:view.getValue()) {
                ObjectNode invitationsRow=(ObjectNode)customer.get("metrics").get("invitations");
                invitationsRow.set("withinRewardRange",unknownCount());
                invitationsRow.set("beyondRewardRange",unknownCount());
                downgradeCount(invitationsRow.get("directCustomers"));
                downgradeCount(invitationsRow.get("descendantCustomers"));
                invitationsRow.put("status","UNKNOWN");
                invitationsRow.set("reasons",upgradeReasons());
            }
        }
        return migrated;
    }

    private static void downgradeCount(JsonNode count) {
        if(!(count instanceof ObjectNode node) || !node.has("observedValue")
                || !node.has("confirmedValue") || !node.has("status"))throw unavailable();
        node.putNull("confirmedValue");
        node.put("status","UNKNOWN");
    }

    private ArrayNode upgradeReasons() {
        ArrayNode reasons=json.createArrayNode();
        reasons.add(UPGRADE_REASON);
        return reasons;
    }

    private ObjectNode unknownCount() {
        ObjectNode count=json.createObjectNode();
        count.putNull("observedValue");
        count.putNull("confirmedValue");
        count.put("status","UNKNOWN");
        return count;
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
                var i=required(m.invitations());required(i.status());count(i.directCustomers());count(i.withinRewardRange());count(i.beyondRewardRange());count(i.descendantCustomers());i.descendantDeposits().forEach(t->money(t.deposits()));
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
