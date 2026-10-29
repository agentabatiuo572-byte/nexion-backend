package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.util.*;

/** Completed customer-level statistics and validated event inputs. Contains no viewer or final HTTP response. */
public record SupportAnalyticsCompiledFacts(Map<String,List<Customer>> customers,
        SupportDeviceReadFacade.Snapshot devices,Map<Long,SupportInvitationReadFacade.Invitation> invitations,
        ActivityWindow activityWindow,ActivityCoverage activityCoverage,Map<Long,CustomerActivity> activities,
        Map<Long,SupportAnalyticsService.NetRead> verifiedNet,List<RootDisplayRow> profiles,List<CustomerTagRow> tags,
        SupportFundsReadFacade.Snapshot funds,SupportRules rules,List<ActivityIdentityRow> identities,List<TaskRow> tasks,List<PendingReplyRow> pending) {
    public SupportAnalyticsCompiledFacts {
        var views=new TreeMap<String,List<Customer>>();customers.forEach((k,v)->views.put(k,List.copyOf(v)));customers=Map.copyOf(views);
        invitations=Map.copyOf(invitations);activities=Map.copyOf(activities);
        verifiedNet=Map.copyOf(verifiedNet);
        profiles=List.copyOf(profiles);tags=List.copyOf(tags);identities=List.copyOf(identities);tasks=List.copyOf(tasks);pending=List.copyOf(pending);
    }
}
