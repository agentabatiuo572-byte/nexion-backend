package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportGroupFacts.Group;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit owned actors and caller-owned customers only; legacy position text never creates a group. */
final class SupportGroupRuntimeFixtures {
    private final SupportFixtureActors actors;
    private final JdbcTemplate jdbc;
    private final SupportGroupMapper mapper;
    private final SupportGroupService service;
    private final Supplier<Set<Long>> ownedCustomers;
    private final Set<Long> groups=new LinkedHashSet<>();
    private final Set<Long> participants=new LinkedHashSet<>();
    private final Set<Long> routedCustomers=new LinkedHashSet<>();
    private final ExactFacts facts;
    private static final String REASON="Explicit owned group scope runtime fixture";

    SupportGroupRuntimeFixtures(SupportFixtureActors actors,JdbcTemplate jdbc,SupportGroupMapper mapper,
            SupportGroupService service,Supplier<Set<Long>> ownedCustomers) {
        this(actors,jdbc,mapper,service,ownedCustomers,null);
    }
    SupportGroupRuntimeFixtures(SupportFixtureActors actors,JdbcTemplate jdbc,SupportGroupMapper mapper,
            SupportGroupService service,Supplier<Set<Long>> ownedCustomers,ExactFacts facts) {
        this.actors=Objects.requireNonNull(actors);this.jdbc=Objects.requireNonNull(jdbc);
        this.mapper=Objects.requireNonNull(mapper);this.service=Objects.requireNonNull(service);
        this.ownedCustomers=Objects.requireNonNull(ownedCustomers);
        this.facts=facts==null?new ExactFacts(jdbc,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),actors,ownedCustomers,()->Set.copyOf(groups),"SupportGroupRuntimeFixtures","ownedGroupCommands",false):facts;
        this.facts.groupIds=()->Set.copyOf(groups);
    }

    /** Caller supplies an authenticated actual super-admin; every changed target has exact creator evidence. */
    Group create(Long owner,Collection<Long> members,String name) {
        return createActual(owner,members,name);
    }
    private Group createActual(Long owner,Collection<Long> members,String name) {
        requireActor(owner);members.forEach(this::requireActor);grant(owner,"SUPERVISOR");
        String createKey=key();var createRequest=new ffdd.opsconsole.content.dto.SupportGroupRequests.Create(name,owner,REASON);
        var group=facts.command("GROUP_CREATE",createKey,createRequest,()->{var result=service.create(createKey,createRequest).getData();if(result!=null&&result.id()!=null)groups.add(result.id());return result;});
        if(group==null || group.id()==null)throw new IllegalStateException("Group fixture creation did not return its ID");
        groups.add(group.id());
        for(Long member:members) {
            grant(member,"SERVICE");var before=mapper.memberCurrent(member);
            if(before!=null && before.groupId()!=null)throw new IllegalStateException("Fixture member already grouped; explicit move required");
            String moveKey=key();var moveRequest=new ffdd.opsconsole.content.dto.SupportGroupRequests.Move(group.id(),before==null?0L:before.version(),null,mapper.group(group.id()).version(),REASON);
            facts.command("GROUP_MEMBER",moveKey,moveRequest,()->service.move(member,moveKey,moveRequest));
        }
        return mapper.group(group.id());
    }

    ffdd.opsconsole.content.domain.SupportGroupFacts.Route route(Long customer,Long group) {
        return facts.mutate("GROUP_ROUTE",()->routeActual(customer,group));
    }
    private ffdd.opsconsole.content.domain.SupportGroupFacts.Route routeActual(Long customer,Long group) {
        if(!ownedCustomers.get().contains(customer) || !groups.contains(group))
            throw new IllegalStateException("Exact caller-owned customer and helper-created group required");
        var before=mapper.routeCurrent(customer);Long source=before==null?null:before.groupId();
        if(source!=null && !groups.contains(source))throw new IllegalStateException("Fixture route source not owned by helper");
        routedCustomers.add(customer);
        String routeKey=key();var routeRequest=new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(group,before==null?0L:before.version(),source==null?null:mapper.group(source).version(),mapper.group(group).version(),REASON);
        return facts.command("GROUP_ROUTE",routeKey,routeRequest,()->service.route(customer,routeKey,routeRequest).getData());
    }

    /** Returned exact IDs let the suite's existing owned cleanup remove group facts before actor cleanup. */
    Set<Long> createdGroupIds() { return Set.copyOf(groups); }
    Set<Long> participatingAdminIds() { return Set.copyOf(participants); }
    ffdd.opsconsole.shared.api.ApiResult<java.util.List<ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification>> qualification(Long actor,String command,ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification request) {
        requireActor(actor);
        return facts.command("GROUP_QUALIFICATION",command,java.util.List.of(actor,request),()->service.qualification(actor,command,request));
    }
    ffdd.opsconsole.shared.api.ApiResult<Group> owner(Long group,String command,ffdd.opsconsole.content.dto.SupportGroupRequests.Owner request) {
        if(!groups.contains(group))throw new IllegalStateException("Owner command requires exact helper-created group");
        requireActor(request.supervisorAdminId());
        return facts.command("GROUP_OWNER",command,java.util.List.of(group,request),()->service.owner(group,command,request));
    }
    ffdd.opsconsole.shared.api.ApiResult<java.util.List<ffdd.opsconsole.content.domain.SupportAssignment>> transfer(SupportBindingService binding,String command,ffdd.opsconsole.content.dto.SupportBindingRequest request) {
        request.customers().forEach(customer->{if(!ownedCustomers.get().contains(customer.id()))throw new IllegalStateException("Transfer requires exact caller-created customer");});
        requireActor(request.targetAgentAdminId());
        return facts.transfer(command,request,()->binding.transfer(command,request));
    }
    void serviceMember(Long member) {facts.mutate("GROUP_QUALIFICATION",()->{requireActor(member);grant(member,"SERVICE");return null;});}
    void asSuper(Long actor,Runnable setup) {
        requireActor(actor);
        var before=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(String.valueOf(actor),null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_write"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m1_write")));
        authentication.setDetails(java.util.Map.of("subjectType","ADMIN","username","group-scope-fixture"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {facts.mutate("GROUP_AUTHENTICATED_COMMANDS",()->{setup.run();return null;});} finally {org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(before);}
    }
    void cleanup() {
        var cleanup=new ArrayList<Runnable>();
        cleanup.add(facts::cleanup);
        for(Long customer:routedCustomers)cleanup.add(()->{
            if(!ownedCustomers.get().contains(customer))throw new IllegalStateException("Fixture route customer ownership missing");
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE customer_id=?",Long.class,customer)).isZero();
        });
        for(Long admin:participants)cleanup.add(()->{
            actors.creationReference(admin);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE agent_admin_id=?",Long.class,admin)).isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history WHERE admin_id=?",Long.class,admin)).isZero();
        });
        for(Long group:groups)cleanup.add(()->{
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_owner_history WHERE group_id=?",Long.class,group)).isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group WHERE id=?",Long.class,group)).isZero();
        });
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }
    private void requireActor(Long id) {actors.creationReference(id);participants.add(id);}
    private void grant(Long id,String kind) {
        var old=mapper.qualification(id,kind);
        if(old!=null && "ENABLED".equals(old.state()))return;
        Long version=jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,id);
        String qualificationKey=key();var request=new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification(kind,"ENABLED",old==null?0L:old.version(),version,REASON);
        facts.command("GROUP_QUALIFICATION",qualificationKey,request,()->service.qualification(id,qualificationKey,request));
    }
    private static String key() { return UUID.randomUUID().toString(); }

    /** Concrete receipt journal for this fixture's seven facts, plus analytics birth/wallet when requested. */
    static final class ExactFacts {
        private static final java.util.List<String> BASE=java.util.List.of("nx_support_customer_route_history","nx_support_agent_user_assignment","nx_support_binding_pool","nx_support_group_member_history","nx_support_account_qualification_history","nx_support_group_owner_history","nx_support_group");
        private final JdbcTemplate jdbc;
        private final com.fasterxml.jackson.databind.ObjectMapper json;
        private final SupportFixtureActors actors;
        private final Supplier<Set<Long>> customers;
        private Supplier<Set<Long>> groupIds;
        private final String suite,testcase;
        private final java.util.List<String> tables;
        private final java.util.Map<String,java.util.List<java.util.Map<String,Object>>> original=new java.util.LinkedHashMap<>();
        private final java.util.Map<String,java.util.Map<Long,java.util.Map<String,Object>>> owned=new java.util.LinkedHashMap<>();
        private final java.util.List<java.util.Map<String,Object>> receipts=new ArrayList<>();
        private final Set<String> requestedCommands=new LinkedHashSet<>();
        private final java.util.Map<String,String> requestedOperations=new java.util.LinkedHashMap<>();
        private final java.util.Map<String,String> requestedHashes=new java.util.LinkedHashMap<>();
        private final java.util.Map<String,ffdd.opsconsole.content.dto.SupportBindingRequest> transferRequests=new java.util.LinkedHashMap<>();
        private final java.util.Map<String,Long> transferActors=new java.util.LinkedHashMap<>();
        private final Set<Long> receiptedGroups=new LinkedHashSet<>();
        @FunctionalInterface interface Call<T> {T execute() throws Exception;}
        ExactFacts(JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper json,SupportFixtureActors actors,
                Supplier<Set<Long>> customers,Supplier<Set<Long>> groups,String suite,String testcase,boolean analytics) {
            this.jdbc=jdbc;this.json=json;this.actors=actors;this.customers=customers;this.groupIds=groups;this.suite=suite;this.testcase=testcase;
            var names=new ArrayList<>(BASE);if(analytics){names.add("nx_support_payment_history_birth");names.add("nx_user_wallet");}else{names.remove("nx_support_agent_user_assignment");names.remove("nx_support_binding_pool");}tables=java.util.List.copyOf(names);
            for(String table:tables){original.put(table,rows(table));owned.put(table,new java.util.LinkedHashMap<>());}
        }
        static String primary(String table){return table.equals("nx_support_binding_pool")||table.equals("nx_support_payment_history_birth")?"customer_id":"id";}
        private long id(String table,java.util.Map<String,Object> row){return ((Number)row.get(primary(table))).longValue();}
        private java.util.List<java.util.Map<String,Object>> rows(String table){return jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+primary(table));}
        private java.util.Map<String,java.util.List<java.util.Map<String,Object>>> snapshot(){var all=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();for(String table:tables)all.put(table,rows(table));return all;}
        java.util.List<java.util.Map<String,Object>> receipts(){return java.util.List.copyOf(receipts);}
        <T>T command(String operation,String key,Object request,Call<T> action){
            proof("PRODUCTION_REQUEST",java.util.Map.of("operation",operation,"idempotencyKey",key,"request",request));requestedCommands.add(key);requestedOperations.put(key,operation);
            // The narrow OWNER/QUALIFICATION caller entries pass the same [target, request]
            // fingerprint that the production GroupService hashes, not a SQL-inferred request.
            if(operation.startsWith("GROUP_")&&request instanceof java.util.List<?>){try{requestedHashes.put(key,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
            return mutate(operation,action);
        }
        <T>T mutate(String operation,Call<T> action) {
                var before=snapshot();var prior=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope LIKE 'SUPPORT_GROUP_%' OR scope LIKE 'SUPPORT_TRANSFER:%' ORDER BY id");
                proof("COMMAND_INTENT",java.util.Map.of("operation",operation,"source","real production fixture command or owned SQL wallet command","before",before,"priorCommands",prior));
                T value=null;Throwable failed=null;try{value=action.execute();}catch(Throwable failure){failed=failure;}
                try{capture(operation,before,prior,snapshot());}catch(Throwable captureFailure){if(failed==null)failed=captureFailure;else failed.addSuppressed(captureFailure);}
                if(failed instanceof Error error)throw error;if(failed instanceof RuntimeException error)throw error;if(failed!=null)throw new IllegalStateException("Owned command failed",failed);return value;
        }
        <T>T transfer(String key,ffdd.opsconsole.content.dto.SupportBindingRequest request,Call<T> action){
            org.assertj.core.api.Assertions.assertThat(tables).doesNotContain("nx_support_agent_user_assignment");
            var authentication=Objects.requireNonNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            long actor=Long.parseLong(authentication.getName());actors.creationReference(actor);
            transferRequests.put(key,request);transferActors.put(key,actor);
            try{requestedHashes.put(key,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
            return command("CUSTOMER_TRANSFER_SQL:"+key,key,request,action);
        }
        private void capture(String operation,java.util.Map<String,java.util.List<java.util.Map<String,Object>>> before,
                java.util.List<java.util.Map<String,Object>> prior,java.util.Map<String,java.util.List<java.util.Map<String,Object>>> after) {
            var priorIds=new LinkedHashSet<Long>();prior.forEach(row->priorIds.add(((Number)row.get("id")).longValue()));
            var correlations=new ArrayList<java.util.Map<String,Object>>();
            if(operation.startsWith("CUSTOMER_TRANSFER_SQL:")&&!Objects.equals(before,after)){
                String key=operation.substring("CUSTOMER_TRANSFER_SQL:".length());var request=Objects.requireNonNull(transferRequests.get(key));long actor=transferActors.get(key);
                var claims=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_TRANSFER:"+actor,key);
                org.assertj.core.api.Assertions.assertThat(claims).hasSize(1);org.assertj.core.api.Assertions.assertThat(claims.get(0).get("status")).isEqualTo("SUCCEEDED");org.assertj.core.api.Assertions.assertThat(priorIds).doesNotContain(((Number)claims.get(0).get("id")).longValue());
                org.assertj.core.api.Assertions.assertThat(claims.get(0).get("request_hash")).isEqualTo(requestedHashes.get(key));
                var requestedCustomers=new LinkedHashSet<Long>();request.customers().forEach(customer->requestedCustomers.add(customer.id()));
                for(String table:tables)if(!table.equals("nx_support_customer_route_history"))org.assertj.core.api.Assertions.assertThat(after.get(table)).isEqualTo(before.get(table));
                var afterRows=new java.util.LinkedHashMap<Long,java.util.Map<String,Object>>();after.get("nx_support_customer_route_history").forEach(row->afterRows.put(id("nx_support_customer_route_history",row),row));
                for(var row:before.get("nx_support_customer_route_history"))if(!Objects.equals(row,afterRows.get(id("nx_support_customer_route_history",row))))org.assertj.core.api.Assertions.assertThat(requestedCustomers).contains(((Number)row.get("customer_id")).longValue());
                org.assertj.core.api.Assertions.assertThat(afterRows).hasSize(before.get("nx_support_customer_route_history").size());
                correlations.add(java.util.Map.of("operation",operation,"request",request,"claim",claims.get(0),"actorCreator",actors.creationReference(actor)));
            }
            // A create response may fail after its transaction committed. A NEW SUCCEEDED requested
            // CREATE claim plus exact owned owner-history row still proves its generated group ID.
            Set<Long> oldOwners=new LinkedHashSet<>();before.get("nx_support_group_owner_history").forEach(row->oldOwners.add(id("nx_support_group_owner_history",row)));
            for(var row:after.get("nx_support_group_owner_history"))if(!oldOwners.contains(id("nx_support_group_owner_history",row))){
                String op=Objects.toString(row.get("operation_id"),"");int split=op.indexOf(':');org.assertj.core.api.Assertions.assertThat(split).isPositive();
                long actor=Long.parseLong(op.substring(0,split));String key=op.substring(split+1);actors.creationReference(actor);actors.creationReference(((Number)row.get("supervisor_admin_id")).longValue());
                org.assertj.core.api.Assertions.assertThat(requestedCommands).contains(key);String kind=ownerKind(requestedOperations.get(key));
                if(kind.equals("OWNER"))org.assertj.core.api.Assertions.assertThat(owned.get("nx_support_group")).containsKey(((Number)row.get("group_id")).longValue());
                var claims=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","SUPPORT_GROUP_"+kind+":"+actor,key);
                org.assertj.core.api.Assertions.assertThat(claims).hasSize(1);org.assertj.core.api.Assertions.assertThat(priorIds).doesNotContain(((Number)claims.get(0).get("id")).longValue());org.assertj.core.api.Assertions.assertThat(claims.get(0).get("status")).isEqualTo("SUCCEEDED");
                if(requestedHashes.containsKey(key))org.assertj.core.api.Assertions.assertThat(claims.get(0).get("request_hash")).isEqualTo(requestedHashes.get(key));
                if(kind.equals("CREATE"))receiptedGroups.add(((Number)row.get("group_id")).longValue());
            }
            for(String table:tables){
                var current=new java.util.LinkedHashMap<Long,java.util.Map<String,Object>>();after.get(table).forEach(row->current.put(id(table,row),row));
                var oldIds=new LinkedHashSet<Long>();
                for(var row:before.get(table)){
                    long identity=id(table,row);oldIds.add(identity);var now=current.get(identity);
                    if(Objects.equals(row,now))continue;
                    // An enclosing authenticated block observes rows already receipted by its nested command.
                    if(now!=null&&Objects.equals(owned.get(table).get(identity),now))continue;
                    if(now==null&&!owned.get(table).containsKey(identity)&&!original.get(table).contains(row)&&table.equals("nx_support_binding_pool"))continue;
                    org.assertj.core.api.Assertions.assertThat(owned.get(table).get(identity)).as("Unrecorded or original fact changed: %s/%s",table,identity).isEqualTo(row);
                    if(now==null){org.assertj.core.api.Assertions.assertThat(table).isEqualTo("nx_support_binding_pool");org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("CUSTOMER_TRANSFER_HTTP");owned.get(table).remove(identity);}
                    else {validateUpdate(table,row,now,operation);owned.get(table).put(identity,now);}
                }
                for(var row:after.get(table))if(!oldIds.contains(id(table,row))){validateNew(table,row,operation,priorIds,correlations);owned.get(table).put(id(table,row),row);}
            }
            // Validate original rows individually even when a legitimate owned row was closed or removed.
            for(String table:tables)for(var row:original.get(table))org.assertj.core.api.Assertions.assertThat(after.get(table)).contains(row);
            proof("COMMAND_READBACK",java.util.Map.of("operation",operation,"before",before,"after",after,"commandCorrelations",correlations));
        }
        static String ownerKind(String operation){org.assertj.core.api.Assertions.assertThat(operation).isIn("GROUP_CREATE","GROUP_OWNER");return operation.equals("GROUP_OWNER")?"OWNER":"CREATE";}
        static void validateUpdate(String table,java.util.Map<String,Object> before,java.util.Map<String,Object> after,String operation){
            String generated=table.equals("nx_support_group_member_history")?"current_agent_id":table.equals("nx_support_account_qualification_history")?"current_admin_id":table.equals("nx_support_group_owner_history")?"current_group_id":table.equals("nx_support_customer_route_history")?"current_customer_id":table.equals("nx_support_agent_user_assignment")?"active_user_id":null;
            var allowed=new LinkedHashSet<String>(java.util.List.of("ends_at"));
            if(table.equals("nx_support_agent_user_assignment"))allowed=new LinkedHashSet<>(java.util.List.of("ends_at","updated_at","version","status"));
            if(table.equals("nx_support_group"))allowed= new LinkedHashSet<>(java.util.List.of("version","updated_at"));
            if(table.equals("nx_support_group")&&operation.equals("GROUP_OWNER"))allowed.add("supervisor_admin_id");
            if(table.equals("nx_user_wallet")){org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("WALLET_VERSION_UPDATE");allowed=new LinkedHashSet<>(java.util.List.of("usdt_available","version","updated_at"));}
            if(generated!=null)allowed.add(generated);
            // Assignment's stored generated value follows status/deletion, not ends_at; validate both readbacks.
            if(table.equals("nx_support_agent_user_assignment"))for(var row:java.util.List.of(before,after)){
                org.assertj.core.api.Assertions.assertThat(row).containsKeys("status","is_deleted","user_id",generated);
                org.assertj.core.api.Assertions.assertThat(row.get("is_deleted")).isInstanceOf(Number.class);
                org.assertj.core.api.Assertions.assertThat(row.get(generated)).isEqualTo("ACTIVE".equals(row.get("status"))&&((Number)row.get("is_deleted")).intValue()==0?row.get("user_id"):null);
            }
            for(String column:before.keySet())if(!Objects.equals(before.get(column),after.get(column)))org.assertj.core.api.Assertions.assertThat(allowed).as("Unexpected mutation column %s.%s",table,column).contains(column);
            if(before.get("version")!=null){long oldVersion=((Number)before.get("version")).longValue(),newVersion=((Number)after.get("version")).longValue();if(table.equals("nx_support_group")||table.equals("nx_support_agent_user_assignment")||table.equals("nx_user_wallet"))org.assertj.core.api.Assertions.assertThat(newVersion).isGreaterThan(oldVersion);else org.assertj.core.api.Assertions.assertThat(newVersion).isEqualTo(oldVersion);}
            if(before.containsKey("ends_at")){org.assertj.core.api.Assertions.assertThat(before.get("ends_at")).isNull();org.assertj.core.api.Assertions.assertThat(after.get("ends_at")).isNotNull();}
            if(generated!=null&&before.containsKey(generated)&&!Objects.equals(before.get(generated),after.get(generated))){org.assertj.core.api.Assertions.assertThat(before.get("ends_at")).isNull();org.assertj.core.api.Assertions.assertThat(after.get("ends_at")).isNotNull();org.assertj.core.api.Assertions.assertThat(after.get(generated)).isNull();}
        }
        private void validateNew(String table,java.util.Map<String,Object> row,String operation,Set<Long> priorIds,java.util.List<java.util.Map<String,Object>> correlations){
            if(table.equals("nx_support_group")){org.assertj.core.api.Assertions.assertThat(receiptedGroups).contains(id(table,row));actors.creationReference(((Number)row.get("supervisor_admin_id")).longValue());return;}
            String ownerColumn=table.equals("nx_support_group_member_history")?"agent_admin_id":table.equals("nx_support_account_qualification_history")?"admin_id":table.equals("nx_support_group_owner_history")?"supervisor_admin_id":null;
            if(ownerColumn!=null)actors.creationReference(((Number)row.get(ownerColumn)).longValue());
            else {String c=table.equals("nx_support_agent_user_assignment")||table.equals("nx_user_wallet")?"user_id":"customer_id";org.assertj.core.api.Assertions.assertThat(customers.get()).contains(((Number)row.get(c)).longValue());}
            if(row.get("group_id")!=null){var allowed=new LinkedHashSet<>(groupIds.get());allowed.addAll(receiptedGroups);org.assertj.core.api.Assertions.assertThat(allowed).contains(((Number)row.get("group_id")).longValue());}
            if(table.equals("nx_support_agent_user_assignment")&&!operation.equals("CUSTOMER_REGISTER"))actors.creationReference(((Number)row.get("agent_admin_id")).longValue());
            if(table.equals("nx_user_wallet")){org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("WALLET_INSERT");return;}
            if(table.equals("nx_support_payment_history_birth")){org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("CUSTOMER_REGISTER");return;}
            if(table.equals("nx_support_binding_pool")){org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("CUSTOMER_REGISTER");return;}
            String factOperation=Objects.toString(row.get("operation_id"),"");org.assertj.core.api.Assertions.assertThat(factOperation).isNotBlank();
            if(table.equals("nx_support_agent_user_assignment")&&operation.equals("CUSTOMER_REGISTER")){
                org.assertj.core.api.Assertions.assertThat(factOperation).isEqualTo("registration:"+row.get("user_id"));return;
            }
            String scope,key;long actor;
            if(table.equals("nx_support_agent_user_assignment")){
                org.assertj.core.api.Assertions.assertThat(operation).isEqualTo("CUSTOMER_TRANSFER_HTTP");key=factOperation;
                var claims=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope LIKE 'SUPPORT_TRANSFER:%' AND idempotency_key=?",key);
                org.assertj.core.api.Assertions.assertThat(claims).hasSize(1);scope=String.valueOf(claims.get(0).get("scope"));actor=Long.parseLong(scope.substring(scope.lastIndexOf(':')+1));
            }else{
                int split=factOperation.indexOf(':');org.assertj.core.api.Assertions.assertThat(split).isPositive();actor=Long.parseLong(factOperation.substring(0,split));key=factOperation.substring(split+1);
                String kind=table.equals("nx_support_group_owner_history")?ownerKind(requestedOperations.get(key)):table.equals("nx_support_account_qualification_history")||table.equals("nx_support_group_member_history")&&row.get("group_id")==null?"QUALIFICATION":table.equals("nx_support_customer_route_history")?"ROUTE":"MEMBER";
                scope="SUPPORT_GROUP_"+kind+":"+actor;
            }
            org.assertj.core.api.Assertions.assertThat(requestedCommands).contains(key);actors.creationReference(actor);var claim=jdbc.queryForList("SELECT * FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?",scope,key);
            org.assertj.core.api.Assertions.assertThat(claim).hasSize(1);org.assertj.core.api.Assertions.assertThat(priorIds).doesNotContain(((Number)claim.get(0).get("id")).longValue());
            org.assertj.core.api.Assertions.assertThat(claim.get(0).get("status")).isEqualTo("SUCCEEDED");org.assertj.core.api.Assertions.assertThat(String.valueOf(claim.get(0).get("request_hash"))).matches("[a-f0-9]{64}");
            if(requestedHashes.containsKey(key))org.assertj.core.api.Assertions.assertThat(claim.get(0).get("request_hash")).isEqualTo(requestedHashes.get(key));
            correlations.add(java.util.Map.of("table",table,"row",row,"operationId",factOperation,"claim",claim.get(0),"actorCreator",actors.creationReference(actor)));
        }
        static String fence(java.util.Map<String,Object> row){org.assertj.core.api.Assertions.assertThat(row).isNotEmpty();row.keySet().forEach(column->org.assertj.core.api.Assertions.assertThat(column).matches("[a-zA-Z0-9_]+"));return String.join(" AND ",row.keySet().stream().map(column->"`"+column+"` <=> ?").toList());}
        void cleanup(){
            var actions=new ArrayList<Runnable>();
            for(String table:tables)for(var row:owned.get(table).values())actions.add(()->{
                var actual=jdbc.queryForList("SELECT * FROM "+table+" WHERE "+primary(table)+"=?",row.get(primary(table)));org.assertj.core.api.Assertions.assertThat(actual).containsExactly(row);
                proof("EXACT_CLEANUP_INTENT",java.util.Map.of("table",table,"primaryKey",primary(table),"row",row));
                org.assertj.core.api.Assertions.assertThat(jdbc.update("DELETE FROM "+table+" WHERE "+fence(row),row.values().toArray())).isEqualTo(1);
                org.assertj.core.api.Assertions.assertThat(independent("SELECT * FROM "+table+" WHERE "+primary(table)+"=?",row.get(primary(table)))).isEmpty();
                proof("EXACT_CLEANUP_READBACK",java.util.Map.of("table",table,"primaryKey",row.get(primary(table)),"absent",true));
            });
            for(String table:tables)actions.add(()->org.assertj.core.api.Assertions.assertThat(independent("SELECT * FROM "+table+" ORDER BY "+primary(table))).isEqualTo(original.get(table)));
            SupportObjectEvidenceLedger.cleanupIndependently(actions.toArray(Runnable[]::new));
        }
        private java.util.List<java.util.Map<String,Object>> independent(String sql,Object...values){
            try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection();var statement=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);var result=new ArrayList<java.util.Map<String,Object>>();try(var rows=statement.executeQuery()){var meta=rows.getMetaData();while(rows.next()){var row=new java.util.LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rows.getObject(i));result.add(row);}}return result;}catch(java.sql.SQLException failure){throw new IllegalStateException("Exact cleanup independent read failed",failure);}
        }
        void proof(String event,Object proof){
            try{var body=new java.util.LinkedHashMap<String,Object>();body.put("event",event);body.put("suite",suite);body.put("testcase",testcase);body.put("workflowRunId",required("WORKFLOW_RUN_ID"));body.put("snapshotHash",required("WORKFLOW_SNAPSHOT_HASH"));body.put("source","SupportGroupRuntimeFixtures.ExactFacts/"+event);body.put("proof",proof);
                var directory=java.nio.file.Path.of(required("CS_ENHANCE_EVIDENCE_DIR"),"owned-analytics-facts");java.nio.file.Files.createDirectories(directory);var file=directory.resolve(testcase+"-"+UUID.randomUUID()+".json.gz");byte[] original=json.writeValueAsBytes(body),bytes=compressProof(original);
                try(var channel=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                org.assertj.core.api.Assertions.assertThat(java.nio.file.Files.readAllBytes(file)).isEqualTo(bytes);receipts.add(java.util.Map.of("path",file.toString(),"event",event,"encoding","gzip","uncompressedBytes",original.length,"sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"uncompressedSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original))));
            }catch(Exception failure){throw new IllegalStateException("Exact owned fact evidence failed",failure);}
        }
        static byte[] compressProof(byte[] original) throws java.io.IOException {
            var output=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(output)){gzip.write(original);}
            byte[] compressed=output.toByteArray();
            try(var input=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))){
                org.assertj.core.api.Assertions.assertThat(input.readAllBytes()).isEqualTo(original);
            }
            return compressed;
        }
        private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing runtime identity: "+name);return value;}
    }
}
