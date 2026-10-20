package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.onboarding.application.PhoneReplacementService.*;
import ffdd.opsconsole.onboarding.mapper.PhoneReplacementMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PhoneReplacementServiceTest {
    static final long NOW=100*PhoneReplacementPolicy.DAY;
    final Account account=Account.production(7);
    final PhoneReplacementMapper mapper=mock(PhoneReplacementMapper.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final EventOutboxService outbox=mock(EventOutboxService.class);
    final PhoneReplacementService service=new PhoneReplacementService(mapper,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC),audit,outbox);
    final Binding binding=new Binding("A",10L,"hw",NOW-PhoneReplacementPolicy.DAY,2);
    final PhoneReplacementPolicy policy=new PhoneReplacementPolicy(true,1,30,2,1);
    @BeforeEach void tx(){TransactionSynchronizationManager.setActualTransactionActive(true);}
    @AfterEach void end(){TransactionSynchronizationManager.setActualTransactionActive(false);}

    LockedAccount locked(PhoneReplacementPolicy p){return new LockedAccount(account,binding,
            new PhoneReplacementMapper.State(null,false,false,null),p,1L,NOW);}
    @Test void sameBindingRestoreBypassesBrokenPolicyButMissingBindingVersionNeverDoes() {
        var input=new ActivationInput(2L,true,1L,false,"A","hw",null);
        assertThat(service.prepareActivation(locked(null),input,List.of()).replacement()).isFalse();
        assertThatThrownBy(()->service.prepareActivation(locked(null),new ActivationInput(null,false,1L,false,"A","hw",null),List.of()))
                .hasMessage("BINDING_VERSION_CHANGED");
        verifyNoInteractions(audit,outbox);
    }
    @Test void firstBindingHasNullBindingVersionAndStillChecksReadablePolicyVersion() {
        var l=new LockedAccount(account,null,null,null,3L,NOW);
        assertThatThrownBy(()->service.prepareActivation(l,new ActivationInput(null,true,2L,false,"A","hw",null),List.of()))
                .hasMessage("POLICY_VERSION_CHANGED");
        assertThat(service.prepareActivation(l,new ActivationInput(null,true,3L,false,"A","hw",null),List.of()).replacement()).isFalse();
    }
    @Test void sameBindingRejectsStaleConfirmationEvenWhenReplacementPolicyIsDisabledOrInvalid() {
        for (PhoneReplacementPolicy current : new PhoneReplacementPolicy[]{
                new PhoneReplacementPolicy(true,1,30,2,8),
                new PhoneReplacementPolicy(false,1,30,2,8), null}) {
            var locked=new LockedAccount(account,binding,null,current,8L,NOW);
            assertThatThrownBy(()->service.prepareActivation(locked,
                    new ActivationInput(2L,true,7L,true,"A","hw",null),List.of()))
                    .hasMessage("POLICY_VERSION_CHANGED");
        }
        verifyNoInteractions(mapper,audit,outbox);
    }
    @Test void sameBindingWithMatchingVersionStillBypassesReplacementRestrictions() {
        for (PhoneReplacementPolicy current : new PhoneReplacementPolicy[]{
                new PhoneReplacementPolicy(false,365,1,1,8), null}) {
            var locked=new LockedAccount(account,binding,null,current,8L,NOW);
            var permit=service.prepareActivation(locked,
                    new ActivationInput(2L,true,8L,true,"A","hw",null),List.of());
            assertThat(permit.replacement()).isFalse();
            assertThat(permit.grant()).isNull();
        }
        verifyNoInteractions(mapper,audit,outbox);
    }
    @Test void sameBindingWithUnreadablePolicyVersionRemainsRecoverableButHardGatesRemain() {
        var locked=new LockedAccount(account,binding,null,null,null,NOW);
        for (Long expected : new Long[]{7L,null}) {
            var input=new ActivationInput(2L,true,expected,true,"A","hw",null);
            assertThat(service.prepareActivation(locked,input,List.of()).replacement()).isFalse();
            assertThatThrownBy(()->service.prepareActivation(locked,input,List.of("PHONE_REPLACEMENT_RISK_PAUSED")))
                    .hasMessage("PHONE_REPLACEMENT_RISK_PAUSED");
        }
        verifyNoInteractions(mapper,audit,outbox);
    }
    @Test void expiryAndTargetMismatchCannotBeWaived() {
        Grant expired=new Grant("g","r",7,"PRODUCTION","","B",2,true,true,NOW-PhoneReplacementPolicy.DAY,NOW,
                "ACTIVE","verified evidence","{}",1,null,null,null);
        when(mapper.currentGrant(account,"g")).thenReturn(expired);
        assertThatThrownBy(()->service.prepareActivation(locked(policy),new ActivationInput(2L,true,1L,true,"B","hw","g"),List.of()))
                .hasMessage("GRANT_STATE_CHANGED");
        verify(mapper,never()).transitionGrant(any(),any(),any(),anyLong(),any(),any());
    }
    @Test void oldSuccessSurvivesLaterGrantExpiryWithOriginalAndCurrentSeparateAndNoEvidenceLeak() throws Exception {
        String original="{\"grantId\":\"g\",\"status\":\"ACTIVE\"}";
        Operation operation=new Operation("o",7,"PRODUCTION","","ADMIN",1,"PHONE_GRANT_ISSUE","key","hash","SUCCEEDED",
                NOW-2*PhoneReplacementPolicy.DAY,NOW-2*PhoneReplacementPolicy.DAY,original,"GRANT","g","audit");
        when(mapper.currentOperation(account,"o")).thenReturn(operation);when(mapper.lockBinding(account)).thenReturn(binding);
        when(mapper.currentGrant(account,"g")).thenReturn(new Grant("g","r",7,"PRODUCTION","","B",2,true,true,0,NOW-1,
                "ACTIVE","valid reason","SECRET INTERNAL EVIDENCE",1,null,null,null));
        Receipt receipt=service.operation(account,"o");
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(((Map<?,?>)receipt.originalResult()).get("status")).isEqualTo("ACTIVE");
        assertThat(((GrantSummary)receipt.currentResource()).status()).isEqualTo("EXPIRED");
        assertThat(new ObjectMapper().writeValueAsString(receipt)).doesNotContain("evidenceJson","SECRET INTERNAL","operatorId","targetInstallationId");
        verifyNoInteractions(audit,outbox);
    }
    @Test void accountScopeCannotCrossProductionAndSandbox() {
        assertThatThrownBy(()->new Account(7,"PRODUCTION","foreign")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Account(7,"SANDBOX","")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Account(7,"SANDBOX","owned-run").runId()).isEqualTo("owned-run");
    }
    @Test void currentAppCheckOccursAfterUserLockBeforeBindingRead() {
        when(mapper.lockUser(7)).thenReturn("ACTIVE");when(mapper.lockBinding(account)).thenReturn(binding);
        Runnable current=mock(Runnable.class);
        service.lockAccount(new LockedPolicy(policy,1L),account,current);
        var ordered=inOrder(mapper,current);
        ordered.verify(mapper).lockUser(7);ordered.verify(current).run();ordered.verify(mapper).lockBinding(account);
    }
    @Test void unknownOperationDoesNotBecomeFailedAndReadCannotMutate() {
        assertThat(service.operation(account,"missing")).isNull();
        verifyNoInteractions(audit,outbox);
        verify(mapper,never()).insertOperation(any(),any(),any(),any(),any(),any(),anyLong());
    }
    void offPolicy() {
        when(mapper.lockPolicy()).thenReturn(List.of(
                new PhoneReplacementMapper.Config(PhoneReplacementPolicy.PREFIX+"allowReplacement","off"),
                new PhoneReplacementMapper.Config(PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays","1"),
                new PhoneReplacementMapper.Config(PhoneReplacementPolicy.PREFIX+"replacementWindowDays","30"),
                new PhoneReplacementMapper.Config(PhoneReplacementPolicy.PREFIX+"maxReplacementsInWindow","2"),
                new PhoneReplacementMapper.Config(PhoneReplacementPolicy.VERSION_KEY,"5")));
    }
    @Test void firstBindingEligibilityMatchesActivationEvenWhenReplacementIsOff() {
        offPolicy();
        var result=service.eligibility(account,"A","hw",true,List.of());
        assertThat(result.binding()).isNull();assertThat(result.canReplace()).isTrue();
        assertThat(result.blockedReasons()).isEmpty();assertThat(result.usedCount()).isZero();
        assertThat(result.policy().policyVersion()).isEqualTo(5L);
        var locked=new LockedAccount(account,null,null,new PhoneReplacementPolicy(false,1,30,2,5),5L,NOW);
        assertThat(service.prepareActivation(locked,new ActivationInput(null,true,5L,true,"A","hw",null),List.of()).replacement()).isFalse();
        verifyNoInteractions(audit,outbox);
    }
    @Test void firstBindingEligibilityPreservesEveryHardGateAndNeverEnablesH5() {
        offPolicy();
        for(String block:List.of("LEGAL_TERMS_ACK_REQUIRED","ONBOARDING_CALIBRATION_REQUIRED","PHONE_CALIBRATION_RULES_CHANGED","PHONE_REPLACEMENT_RISK_PAUSED")) {
            var result=service.eligibility(account,"A","hw",true,List.of(block));
            assertThat(result.canReplace()).as(block).isFalse();assertThat(result.blockedReasons()).containsExactly(block);
        }
        var h5=service.eligibility(account,null,null,false,List.of());
        assertThat(h5.canReplace()).isFalse();assertThat(h5.blockedReasons()).containsExactly("CURRENT_APP_REQUIRED");
    }
    @Test void sameBindingRecoveryIgnoresReplacementRulesButPreservesRiskAndCalibrationGates() {
        offPolicy();when(mapper.lockBinding(account)).thenReturn(binding);
        var result=service.eligibility(account,"A","hw",true,List.of());
        assertThat(result.canReplace()).isTrue();assertThat(result.blockedReasons()).isEmpty();
        assertThat(result.usedCount()).isNull(); // Unknown historical usage is not fabricated as zero.
        for(String block:List.of("PHONE_REPLACEMENT_RISK_PAUSED","ONBOARDING_CALIBRATION_REQUIRED","LEGAL_TERMS_ACK_REQUIRED")) {
            var blocked=service.eligibility(account,"A","hw",true,List.of(block));
            assertThat(blocked.canReplace()).isFalse();assertThat(blocked.blockedReasons()).containsExactly(block);
        }
    }
    @Test void changedOrUnknownHardwareAndDifferentInstallationAreNeverRecovery() {
        offPolicy();when(mapper.lockBinding(account)).thenReturn(binding);
        for(String hardware:new String[]{"changed-hardware",null,""}) {
            var result=service.eligibility(account,"A",hardware,true,List.of());
            assertThat(result.canReplace()).isFalse();assertThat(result.blockedReasons()).contains("PHONE_REPLACEMENT_DISABLED");
        }
        var other=service.eligibility(account,"B","hw",true,List.of());
        assertThat(other.canReplace()).isFalse();assertThat(other.blockedReasons()).contains("PHONE_REPLACEMENT_DISABLED");
    }
    @Test void unavailableReplacementPolicyDoesNotBlockFirstBindingOrKnownRecoveryAndKeepsVersion() {
        when(mapper.lockPolicy()).thenReturn(List.of(new PhoneReplacementMapper.Config(PhoneReplacementPolicy.VERSION_KEY,"5")));
        var first=service.eligibility(account,"A","hw",true,List.of());
        assertThat(first.canReplace()).isTrue();assertThat(first.policy().available()).isFalse();
        assertThat(first.policy().policyVersion()).isEqualTo(5L);
        when(mapper.lockBinding(account)).thenReturn(binding);
        var restore=service.eligibility(account,"A","hw",true,List.of());
        assertThat(restore.canReplace()).isTrue();assertThat(restore.policy().policyVersion()).isEqualTo(5L);
        var other=service.eligibility(account,"B","hw",true,List.of());
        assertThat(other.canReplace()).isFalse();assertThat(other.blockedReasons()).contains("PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
    }

}
