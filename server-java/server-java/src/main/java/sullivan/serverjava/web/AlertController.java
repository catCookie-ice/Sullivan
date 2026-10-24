package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AlertService;
import sullivan.serverjava.service.AlertSettingService;
import sullivan.serverjava.service.LocationService;
import sullivan.serverjava.web.AlertDtos.AlertCreateRequest;
import sullivan.serverjava.web.AlertDtos.AlertLocation;
import sullivan.serverjava.web.AlertDtos.AlertResponse;
import sullivan.serverjava.web.AlertDtos.AlertSettingResponse;
import sullivan.serverjava.web.AlertDtos.AlertSettingUpdateRequest;
import sullivan.serverjava.web.AlertDtos.LocationReportRequest;

import java.util.List;

/**
 * 报警与位置 REST 面。
 *
 * <p>位置单独一个端点（{@code PUT /me/location}）：它比报警频繁得多，且与报警无关 ——
 * 用户正常走路时也在上报，报警时只是**读**当时的最新位置。把两者合成一个接口会让
 * 「上报位置」被迫携带一个报警 ID，语义就乱了。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "报警与位置", description = "需登录；仅自然人。报警会按生效监护人顺序路由。")
@SecurityRequirement(name = "bearerAuth")
public class AlertController {

    private final AlertService alertService;
    private final AlertSettingService settingService;
    private final LocationService locationService;

    public AlertController(AlertService alertService, AlertSettingService settingService,
                          LocationService locationService) {
        this.alertService = alertService;
        this.settingService = settingService;
        this.locationService = locationService;
    }

    @PutMapping("/me/location")
    @Operation(summary = "上报最新位置",
            description = "覆盖式写入，只保留最新一条。受护人端在报警前周期性上报，报警时读取该快照。")
    public AlertLocation reportLocation(Authentication authentication,
                                        @Valid @RequestBody LocationReportRequest request) {
        return locationService.report(principal(authentication).id(), request);
    }

    @GetMapping("/me/location")
    @Operation(summary = "读取我的最新位置", description = "从未上报过时返回 404。")
    public AlertLocation myLocation(Authentication authentication) {
        AlertLocation location = locationService.latestOf(principal(authentication).id());
        if (location == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "尚未上报位置");
        }
        return location;
    }

    @PostMapping("/alerts")
    @Operation(summary = "创建报警",
            description = "报警先落库再路由；存在生效监护人时通知全部监护人，并在 5 秒后"
                    + "按绑定确认时间依次发起实时会话（可由用户关闭）。")
    public ResponseEntity<AlertResponse> create(Authentication authentication,
                                                @Valid @RequestBody AlertCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertService.create(principal(authentication).id(), request));
    }

    @GetMapping("/alerts")
    @Operation(summary = "我的报警列表", description = "受护人视角，按创建时间倒序。")
    public List<AlertResponse> myAlerts(Authentication authentication) {
        return alertService.myAlerts(principal(authentication).id());
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "报警详情", description = "仅受护人本人与其生效监护人可读；其他人一律 404。")
    public AlertResponse get(Authentication authentication,
                             @Parameter(description = "报警唯一标识", example = "al_1234567890")
                             @PathVariable String alertId) {
        return alertService.get(principal(authentication).id(), alertId);
    }

    @PostMapping("/alerts/{alertId}/resolve")
    @Operation(summary = "结束报警", description = "仅受护人本人；同时取消尚未执行的自动呼叫。")
    public AlertResponse resolve(Authentication authentication,
                                 @Parameter(description = "报警唯一标识", example = "al_1234567890")
                                 @PathVariable String alertId) {
        return alertService.resolve(principal(authentication).id(), alertId);
    }

    @GetMapping("/me/alert-setting")
    @Operation(summary = "读取报警偏好",
            description = "从未配置过时返回默认值：自动呼叫开启、延迟 5 秒。")
    public AlertSettingResponse mySetting(Authentication authentication) {
        return settingService.get(principal(authentication).id());
    }

    @PutMapping("/me/alert-setting")
    @Operation(summary = "修改报警偏好",
            description = "关闭后，已排期但尚未执行的自动呼叫会被一并取消。")
    public AlertSettingResponse updateSetting(Authentication authentication,
                                              @Valid @RequestBody AlertSettingUpdateRequest request) {
        String uid = principal(authentication).id();
        AlertSettingResponse updated = settingService.update(uid, request.autoSessionEnabled());
        if (!request.autoSessionEnabled()) {
            // 光改配置不够：用户在这一刻关掉，意味着「刚才那条报警也别呼人」。
            // 不取消的话，5 秒内已经排期的报警仍会响，用户会觉得开关没生效。
            alertService.skipScheduled(uid);
        }
        return updated;
    }

    @GetMapping("/me/alert-guardians")
    @Operation(summary = "报警会依次通知谁",
            description = "按将要呼叫的顺序返回生效监护人 ID；受护人端用来展示「会通知谁」。")
    public List<String> alertGuardians(Authentication authentication) {
        return alertService.guardianOrder(principal(authentication).id());
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以使用报警与位置功能");
        }
        return principal;
    }
}
