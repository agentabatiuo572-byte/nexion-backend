package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneReplacementPolicyTest {
    static final long DAY=PhoneReplacementPolicy.DAY, NOW=100*DAY;
    static final PhoneReplacementPolicy POLICY=new PhoneReplacementPolicy(true,1,30,2,7);
    static final PhoneReplacementPolicy.History KNOWN=new PhoneReplacementPolicy.History(0L,true,true);

    @Test void exactIntervalAndOpenLeftBoundaryUseServerTime() {
        var d=POLICY.evaluate(NOW,NOW-DAY,KNOWN,List.of(NOW-30*DAY,NOW-DAY),false,false,List.of());
        assertThat(d.canReplace()).isTrue(); assertThat(d.usedCount()).isEqualTo(1); assertThat(d.remainingCount()).isEqualTo(1);
        var before=POLICY.evaluate(NOW-1,NOW-DAY,KNOWN,List.of(NOW-30*DAY,NOW-DAY),false,false,List.of());
        assertThat(before.blockedReasons()).contains("PHONE_REPLACEMENT_COOLDOWN","PHONE_REPLACEMENT_LIMIT_REACHED");
    }
    @Test void overLimitNeedsEnoughEventsToExpireAndNeverNegativeRemaining() {
        var d=POLICY.evaluate(NOW,NOW-DAY,KNOWN,List.of(NOW-29*DAY,NOW-20*DAY,NOW-10*DAY,NOW-DAY),false,false,List.of());
        assertThat(d.remainingCount()).isZero(); assertThat(d.nextAllowedAt()).isEqualTo(NOW+20*DAY);
    }
    @Test void unknownCountAndTimeRequireIndependentWaiversWithoutErasingUnknown() {
        var h=new PhoneReplacementPolicy.History(null,false,false);
        var partial=POLICY.evaluate(NOW,null,h,List.of(),true,false,List.of());
        assertThat(partial.usedCount()).isNull(); assertThat(partial.remainingCount()).isNull();
        assertThat(partial.blockedReasons()).containsExactly("PHONE_BINDING_TIME_UNKNOWN");
        var both=POLICY.evaluate(NOW,null,h,List.of(NOW-1),true,true,List.of());
        assertThat(both.canReplace()).isTrue(); assertThat(both.historyComplete()).isFalse(); assertThat(both.nextAllowedAt()).isNull();
    }
    @Test void reliableCoverageMaturesAndWindowExpansionCanExposeUnknownAgain() {
        var h=new PhoneReplacementPolicy.History(NOW-30*DAY,false,true);
        assertThat(POLICY.evaluate(NOW,NOW-DAY,h,List.of(),false,false,List.of()).historyComplete()).isTrue();
        assertThat(new PhoneReplacementPolicy(true,1,31,2,8).evaluate(NOW,NOW-DAY,h,List.of(),false,false,List.of()).usedCount()).isNull();
        assertThat(POLICY.evaluate(NOW-1,NOW-DAY,h,List.of(),false,false,List.of()).usedCount()).isNull();
    }
    @Test void grantsCannotBypassDisabledOrOtherHardBlocks() {
        var disabled=new PhoneReplacementPolicy(false,1,30,2,7).evaluate(NOW,null,null,List.of(),true,true,List.of("RISK_PAUSED"));
        assertThat(disabled.canReplace()).isFalse(); assertThat(disabled.nextAllowedAt()).isNull();
        assertThat(disabled.blockedReasons()).contains("RISK_PAUSED","PHONE_REPLACEMENT_DISABLED");
    }
    @Test void malformedStoredPolicyIsUnavailableRatherThanThrowingOrReturningZero() {
        for(var p:List.of(new PhoneReplacementPolicy(true,Long.MAX_VALUE,30,2,1),
                new PhoneReplacementPolicy(true,9_007_199_254_740_991L,30,2,1),
                new PhoneReplacementPolicy(true,1,30,0,1),new PhoneReplacementPolicy(true,1,0,2,1))) {
            var d=p.evaluate(NOW,NOW-DAY,KNOWN,List.of(NOW-1),true,true,List.of());
            assertThat(d.canReplace()).isFalse();assertThat(d.usedCount()).isNull();assertThat(d.remainingCount()).isNull();
            assertThat(d.blockedReasons()).contains("PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
        }
        assertThat(PhoneReplacementPolicy.parse(Map.of(),NOW)).isNull();
    }
    @Test void zeroIntervalDoesNotRemoveHistoryRequirementAndInstallationIdentityIsCaseSensitive() {
        var d=new PhoneReplacementPolicy(true,0,30,2,1).evaluate(NOW,null,null,List.of(),false,false,List.of());
        assertThat(d.blockedReasons()).containsExactly("PHONE_REPLACEMENT_HISTORY_UNKNOWN");
        assertThat(PhoneReplacementPolicy.isReplacement(null,null,"A","h")).isFalse();
        assertThat(PhoneReplacementPolicy.isReplacement("A","","A","h")).isFalse();
        assertThat(PhoneReplacementPolicy.isReplacement("A","h","A","h")).isFalse();
        assertThat(PhoneReplacementPolicy.isReplacement("A","h","a","h")).isTrue();
        assertThat(PhoneReplacementPolicy.isReplacement("A","h","A","new")).isTrue();
        assertThat(PhoneReplacementPolicy.isReplacement("B","h","A","h")).isTrue();
    }
}
