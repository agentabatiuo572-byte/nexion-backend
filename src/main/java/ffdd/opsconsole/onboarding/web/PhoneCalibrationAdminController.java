package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.AndroidSocPresetCatalog;
import ffdd.opsconsole.onboarding.application.IosModelPresetCatalog;
import ffdd.opsconsole.onboarding.application.PhoneCalibrationConfigService;
import ffdd.opsconsole.onboarding.application.PhoneCalibrationPolicy;
import ffdd.opsconsole.onboarding.application.PhoneModelRisk;
import ffdd.opsconsole.onboarding.application.PhoneModelRiskAdminService;
import ffdd.opsconsole.onboarding.application.PhoneModelRiskConfig;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/config/phone-calibration")
@RequiredArgsConstructor
public class PhoneCalibrationAdminController {
    private final PlatformConfigFacade config;
    private final OnboardingCalibrationMapper mapper;
    private final Clock clock;
    private final PhoneModelRiskAdminService risk;
    /** {@code facts} optionally adds an iPhone's OS version and screen to show which model checks would fire. */
    public record Preview(PhoneCalibrationConfigService.Proposal proposal, PhoneCalibrationPolicy.Hardware hardware,
                          PhoneModelRisk.Facts facts) {
        public Preview(PhoneCalibrationConfigService.Proposal proposal, PhoneCalibrationPolicy.Hardware hardware) {
            this(proposal, hardware, null);
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('device_e6_read','device_e2_read')")
    public ApiResult<Map<String,Object>> read() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("policy", new PhoneCalibrationConfigService(config, clock).read());
        data.put("pendingHardware", mapper.pendingHardware());
        data.put("computeUnit", "platform");
        return ApiResult.ok(data);
    }

    /** The versioned iPhone preset catalog; loading it only fills an E6 draft that still goes through A2. */
    @GetMapping("/ios-model-presets")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<IosModelPresetCatalog.Catalog> iosModelPresets() {
        return ApiResult.ok(IosModelPresetCatalog.load());
    }

    /** The versioned Android chip-tier presets; loading them only fills an E6 draft that still goes through A2. */
    @GetMapping("/android-soc-presets")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<AndroidSocPresetCatalog.Catalog> androidSocPresets() {
        return ApiResult.ok(AndroidSocPresetCatalog.load());
    }

    /** Model risk settings take effect when saved; they are not part of the A2-approved rules. */
    @GetMapping("/risk")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<Map<String,Object>> risk() {
        return ApiResult.ok(risk.read());
    }

    @PutMapping("/risk")
    @PreAuthorize("hasAuthority('device_e6_write')")
    public ApiResult<Map<String,Object>> updateRisk(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   @RequestBody PhoneModelRiskAdminService.Update request) {
        return ApiResult.ok(risk.update(request, key));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<Map<String,Object>> preview(@RequestBody Preview request) {
        if (request == null || request.proposal() == null) return ApiResult.fail(422, "PHONE_CALIBRATION_POLICY_INVALID");
        var service = new PhoneCalibrationConfigService(config, clock);
        var policy = service.candidate(request.proposal(), service.read());
        long matched = 0, changed = 0, pending = 0;
        // Groups split per allocated value, so many groups share one hardware match.
        Map<PhoneCalibrationPolicy.Hardware, PhoneCalibrationPolicy.Match> matches = new java.util.HashMap<>();
        for (var group : mapper.calibrationHardwareGroups()) {
            var result = matches.computeIfAbsent(new PhoneCalibrationPolicy.Hardware(group.platform(), group.model(),
                    group.socIdList()), policy::match);
            if (!result.matched()) pending += group.count();
            else {
                matched += group.count();
                // A stored allocation stays valid while it is inside the new range; nothing is drawn here.
                boolean same = PhoneCalibrationPolicy.within(result, group.computeValue())
                        && Integer.valueOf(policy.tierFor(group.computeValue())).equals(group.tier());
                if (!same) changed += group.count();
            }
        }
        var match = policy.match(request.hardware());
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("match", match);
        data.put("possibleTiers", policy.possibleTiers(match));
        // Only the per-report checks: the installation lock and account churn depend on a real account's history.
        var rule = match.matched() && match.range() ? policy.iosRule(match.ruleId()) : null;
        data.put("risk", request.facts() == null || rule == null ? List.of()
                : PhoneModelRisk.evaluate(new PhoneModelRiskConfig(config, clock).read(),
                        new PhoneModelRisk.Facts(request.hardware().platform(), request.facts().osVersion(), request.facts().pixelRatio(),
                                request.facts().screenWidth(), request.facts().screenHeight()), rule, false, 0).findings());
        data.put("impact", Map.of("matched", matched, "changed", changed, "pending", pending,
                "appliesTo", "NEXT_CALIBRATION", "historicalSettlementChanged", false));
        data.put("expectedRevision", request.proposal().expectedRevision());
        return ApiResult.ok(data);
    }
}
