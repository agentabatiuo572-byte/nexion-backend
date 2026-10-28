package ffdd.opsconsole.onboarding.web;

import ffdd.opsconsole.onboarding.application.IosModelPresetCatalog;
import ffdd.opsconsole.onboarding.application.PhoneCalibrationConfigService;
import ffdd.opsconsole.onboarding.application.PhoneCalibrationPolicy;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public record Preview(PhoneCalibrationConfigService.Proposal proposal, PhoneCalibrationPolicy.Hardware hardware) { }

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

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('device_e6_read')")
    public ApiResult<Map<String,Object>> preview(@RequestBody Preview request) {
        if (request == null || request.proposal() == null) return ApiResult.fail(422, "PHONE_CALIBRATION_POLICY_INVALID");
        var service = new PhoneCalibrationConfigService(config, clock);
        var policy = service.candidate(request.proposal(), service.read());
        long matched = 0, changed = 0, pending = 0;
        // iPhone groups split per allocated value, so many groups share one hardware match.
        Map<PhoneCalibrationPolicy.Hardware, PhoneCalibrationPolicy.Match> matches = new java.util.HashMap<>();
        for (var group : mapper.calibrationHardwareGroups()) {
            var result = matches.computeIfAbsent(new PhoneCalibrationPolicy.Hardware(group.platform(), group.model(),
                    group.soc(), group.gpu(), group.memoryGb()), policy::match);
            if (!result.matched()) pending += group.count();
            else {
                matched += group.count();
                // A stored iPhone allocation stays valid while it is inside the new range; nothing is drawn here.
                boolean same = result.range()
                        ? PhoneCalibrationPolicy.within(result, group.computeValue())
                                && Integer.valueOf(policy.tierFor(group.computeValue())).equals(group.tier())
                        : result.tier().equals(group.tier()) && group.computeValue() != null
                                && result.computeValue().compareTo(group.computeValue()) == 0;
                if (!same) changed += group.count();
            }
        }
        var match = policy.match(request.hardware());
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("match", match);
        data.put("possibleTiers", policy.possibleTiers(match));
        data.put("impact", Map.of("matched", matched, "changed", changed, "pending", pending,
                "appliesTo", "NEXT_CALIBRATION", "historicalSettlementChanged", false));
        data.put("expectedRevision", request.proposal().expectedRevision());
        return ApiResult.ok(data);
    }
}
