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
    private static final String REASON="Explicit owned group scope runtime fixture";

    SupportGroupRuntimeFixtures(SupportFixtureActors actors,JdbcTemplate jdbc,SupportGroupMapper mapper,
            SupportGroupService service,Supplier<Set<Long>> ownedCustomers) {
        this.actors=Objects.requireNonNull(actors);this.jdbc=Objects.requireNonNull(jdbc);
        this.mapper=Objects.requireNonNull(mapper);this.service=Objects.requireNonNull(service);
        this.ownedCustomers=Objects.requireNonNull(ownedCustomers);
    }

    /** Caller supplies an authenticated actual super-admin; every changed target has exact creator evidence. */
    Group create(Long owner,Collection<Long> members,String name) {
        requireActor(owner);members.forEach(this::requireActor);grant(owner,"SUPERVISOR");
        var group=service.create(key(),new ffdd.opsconsole.content.dto.SupportGroupRequests.Create(name,owner,REASON)).getData();
        if(group==null || group.id()==null)throw new IllegalStateException("Group fixture creation did not return its ID");
        groups.add(group.id());
        for(Long member:members) {
            grant(member,"SERVICE");var before=mapper.memberCurrent(member);
            if(before!=null && before.groupId()!=null)throw new IllegalStateException("Fixture member already grouped; explicit move required");
            service.move(member,key(),new ffdd.opsconsole.content.dto.SupportGroupRequests.Move(group.id(),
                    before==null?0L:before.version(),null,mapper.group(group.id()).version(),REASON));
        }
        return mapper.group(group.id());
    }

    ffdd.opsconsole.content.domain.SupportGroupFacts.Route route(Long customer,Long group) {
        if(!ownedCustomers.get().contains(customer) || !groups.contains(group))
            throw new IllegalStateException("Exact caller-owned customer and helper-created group required");
        var before=mapper.routeCurrent(customer);Long source=before==null?null:before.groupId();
        if(source!=null && !groups.contains(source))throw new IllegalStateException("Fixture route source not owned by helper");
        routedCustomers.add(customer);
        return service.route(customer,key(),new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(group,
                before==null?0L:before.version(),source==null?null:mapper.group(source).version(),mapper.group(group).version(),REASON)).getData();
    }

    /** Returned exact IDs let the suite's existing owned cleanup remove group facts before actor cleanup. */
    Set<Long> createdGroupIds() { return Set.copyOf(groups); }
    Set<Long> participatingAdminIds() { return Set.copyOf(participants); }
    void serviceMember(Long member) {requireActor(member);grant(member,"SERVICE");}
    void asSuper(Long actor,Runnable setup) {
        requireActor(actor);
        var before=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(String.valueOf(actor),null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("platform_a1_write"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m1_write")));
        authentication.setDetails(java.util.Map.of("subjectType","ADMIN","username","group-scope-fixture"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {setup.run();} finally {org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(before);}
    }
    void cleanup() {
        var cleanup=new ArrayList<Runnable>();
        for(Long customer:routedCustomers)cleanup.add(()->{
            if(!ownedCustomers.get().contains(customer))throw new IllegalStateException("Fixture route customer ownership missing");
            jdbc.update("DELETE FROM nx_support_customer_route_history WHERE customer_id=?",customer);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE customer_id=?",Long.class,customer)).isZero();
        });
        for(Long admin:participants)cleanup.add(()->{
            actors.creationReference(admin);
            jdbc.update("DELETE FROM nx_support_group_member_history WHERE agent_admin_id=?",admin);
            jdbc.update("DELETE FROM nx_support_account_qualification_history WHERE admin_id=?",admin);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_group_member_history WHERE agent_admin_id=?",Long.class,admin)).isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_account_qualification_history WHERE admin_id=?",Long.class,admin)).isZero();
        });
        for(Long group:groups)cleanup.add(()->{
            jdbc.update("DELETE FROM nx_support_group_owner_history WHERE group_id=?",group);
            jdbc.update("DELETE FROM nx_support_group WHERE id=?",group);
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
        service.qualification(id,key(),new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification(
                kind,"ENABLED",old==null?0L:old.version(),version,REASON));
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
