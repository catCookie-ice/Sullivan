package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

public final class RegistrationDtos {
    private RegistrationDtos() {
    }

    public record UserRegisterRequest(
            @Schema(description = "登录名，全局唯一", example = "zhangsan",
                    minLength = 4, maxLength = 64)
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @Schema(description = "登录密码，后端以 BCrypt 哈希存储", example = "abc12345",
                    minLength = 8, maxLength = 72)
            @NotBlank @Size(min = 8, max = 72) String password,
            @Schema(description = "显示名（昵称）", example = "张三", maxLength = 80)
            @NotBlank @Size(max = 80) String displayName,
            @Schema(description = "注册角色：GUARDIAN（监护人）或 VOLUNTEER（志愿者）",
                    example = "GUARDIAN", allowableValues = {"GUARDIAN", "VOLUNTEER"})
            @Pattern(regexp = "GUARDIAN|VOLUNTEER") String role,
            @Schema(description = "找回手机号（可选；填写后可用于找回密码，留空将导致密码无法找回）",
                    example = "13800138000", maxLength = 16)
            @Size(max = 16) @Pattern(regexp = "^$|^1[3-9]\\d{9}$",
                    message = "手机号格式不正确") String recoveryPhone,
            @Schema(description = "找回邮箱（可选；填写后可用于找回密码，留空将导致密码无法找回）",
                    example = "zhangsan@example.com", maxLength = 128)
            @Size(max = 128) @Pattern(regexp = "^$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                    message = "邮箱格式不正确") String recoveryEmail) {
    }

    public record ProtecteeRegisterRequest(
            @Schema(description = "受护人登录名，全局唯一", example = "protectee_wang",
                    minLength = 4, maxLength = 64)
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @Schema(description = "受护人登录密码，后端以 BCrypt 哈希存储", example = "abc12345",
                    minLength = 8, maxLength = 72)
            @NotBlank @Size(min = 8, max = 72) String password,
            @Schema(description = "受护人显示名", example = "王小明", maxLength = 80)
            @NotBlank @Size(max = 80) String displayName,
            @Schema(description = "应急备注（可选），如健康情况、紧急联络方式等",
                    example = "对花生过敏；紧急联系人李女士 13800000000", maxLength = 500)
            @Size(max = 500) String emergencyNote) {
    }

    public record BindingRequest(
            @Schema(description = "目标受护人的账号唯一标识", example = "u_9876543210")
            @NotBlank String protecteeId) {
    }

    /**
     * 绑定关系。
     *
     * <p>同时带双方 uid 与名字：uid 保留是因为客户端要靠它判断「我是哪一方」，
     * 而名字是**界面唯一可显示的东西**（00 §7 第 11 条）。此前只有 uid，
     * 于是绑定列表只能显示一串 26 位随机串，既看不懂也念不出来。
     */
    public record BindingResponse(
            @Schema(description = "绑定关系唯一标识", example = "b_1234567890") String id,
            @Schema(description = "绑定状态：PENDING（待确认）/ ACTIVE（已生效）",
                    example = "ACTIVE", allowableValues = {"PENDING", "ACTIVE", "REJECTED",
                            "CANCELLED", "EXPIRED", "TERMINATED"}) String status,
            @Schema(description = "监护人账号 ID（内部主键，界面不显示）",
                    example = "u_1111111111") String guardianId,
            @Schema(description = "受护人账号 ID（内部主键，界面不显示）",
                    example = "u_9876543210") String protecteeId,
            @Schema(description = "是否永久绑定（创建受护人时建立的主绑定为 true）",
                    example = "true") boolean permanent,
            @Schema(description = "是否主绑定：受护人的第一监护关系", example = "true") boolean primary,
            @Schema(description = "发起方账号 ID：等于 guardianId 表示监护人发起，等于 protecteeId 表示受护人发起",
                    example = "u_1111111111") String requestedBy,
            @Schema(description = "监护人登录名（界面显示用）", example = "guardian_li") String guardianLoginName,
            @Schema(description = "受护人登录名（界面显示用）", example = "protectee_wang")
            String protecteeLoginName,
            @Schema(description = "监护人显示名（优先用于界面）", example = "李监护") String guardianName,
            @Schema(description = "受护人显示名（优先用于界面）", example = "王小明") String protecteeName) {
    }

    public record OrganizationRegisterRequest(
            @Schema(description = "机构登录名，全局唯一", example = "blindsociety",
                    minLength = 4, maxLength = 64)
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @Schema(description = "机构登录密码，后端以 BCrypt 哈希存储", example = "abc12345",
                    minLength = 8, maxLength = 72)
            @NotBlank @Size(min = 8, max = 72) String password,
            @Schema(description = "机构注册全称（法律名称）", example = "北京市朝阳区盲人协会",
                    maxLength = 160)
            @NotBlank @Size(max = 160) String legalName,
            @Schema(description = "机构对外展示名称", example = "朝阳盲协", maxLength = 160)
            @NotBlank @Size(max = 160) String publicName,
            @Schema(description = "找回手机号（可选；填写后可用于找回密码，留空将导致密码无法找回）",
                    example = "13800138000", maxLength = 16)
            @Size(max = 16) @Pattern(regexp = "^$|^1[3-9]\\d{9}$",
                    message = "手机号格式不正确") String recoveryPhone,
            @Schema(description = "找回邮箱（可选；填写后可用于找回密码，留空将导致密码无法找回）",
                    example = "contact@example.com", maxLength = 128)
            @Size(max = 128) @Pattern(regexp = "^$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                    message = "邮箱格式不正确") String recoveryEmail) {
    }

    public record AdminRegisterRequest(
            @Schema(description = "管理员登录名，全局唯一", example = "admin01",
                    minLength = 4, maxLength = 64)
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @Schema(description = "管理员登录密码，后端以 BCrypt 哈希存储", example = "abc12345",
                    minLength = 8, maxLength = 72)
            @NotBlank @Size(min = 8, max = 72) String password,
            @Schema(description = "管理员显示名", example = "张管理", maxLength = 80)
            @NotBlank @Size(max = 80) String displayName,
            @Schema(description = "一次性注册码（SV- 开头，由最高/HR 管理员签发）",
                    example = "SV-5fYkR0xMqB7cNvLzW9Tp")
            @NotBlank String registrationCode) {
    }

    public record AdminCodeCreateRequest(
            @Schema(description = "初始管理员角色代码，须为当前 ACTIVE 的角色",
                    example = "HR_ADMIN", allowableValues = {"SUPER_ADMIN", "HR_ADMIN", "AUDITOR"})
            @NotBlank String initialRoleCode,
            @Schema(description = "注册码过期时间，须为未来时间，格式 yyyy-MM-dd HH:mm:ss",
                    example = "2026-09-01 00:00:00")
            @Future LocalDateTime expiresAt) {
    }

    public record LoginRequest(
            @Schema(description = "登录主体类型：USER（自然人）/ ORGANIZATION（机构）/ ADMIN（管理员）",
                    example = "USER", allowableValues = {"USER", "ORGANIZATION", "ADMIN"})
            @NotNull
            @Pattern(regexp = "USER|ORGANIZATION|ADMIN") String principalType,
            @Schema(description = "登录名", example = "zhangsan")
            @NotBlank String loginName,
            @Schema(description = "登录密码", example = "abc12345")
            @NotBlank String password) {
    }

    public record RegistrationResponse(
            @Schema(description = "主体唯一标识", example = "u_1234567890") String id,
            @Schema(description = "主体类型：USER / ORGANIZATION / ADMIN",
                    example = "USER", allowableValues = {"USER", "ORGANIZATION", "ADMIN"}) String principalType,
            @Schema(description = "账号状态：ENABLED（启用）/ PENDING（待审核）",
                    example = "ENABLED", allowableValues = {"ENABLED", "PENDING", "DISABLED"}) String status,
            @Schema(description = "已拥有的角色集合", example = "[\"GUARDIAN\"]") Set<String> roles) {
    }

    public record AdminCodeResponse(
            @Schema(description = "注册码明文（仅本次创建时返回，后端不保存明文）",
                    example = "SV-5fYkR0xMqB7cNvLzW9Tp") String code,
            @Schema(description = "该注册码对应的初始角色代码", example = "HR_ADMIN") String initialRoleCode,
            @Schema(description = "注册码过期时间，格式 yyyy-MM-dd HH:mm:ss",
                    example = "2026-09-01 00:00:00") LocalDateTime expiresAt) {
    }

    public record TokenResponse(
            @Schema(description = "JWT 访问令牌，请求时放入 Authorization: Bearer <token>",
                    example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
            @Schema(description = "主体唯一标识", example = "u_1234567890") String principalId,
            @Schema(description = "主体类型：USER / ORGANIZATION / ADMIN",
                    example = "USER", allowableValues = {"USER", "ORGANIZATION", "ADMIN"}) String principalType,
            @Schema(description = "角色集合", example = "[\"GUARDIAN\"]") Set<String> roles,
            @Schema(description = "权限集合（管理员登录时非空）",
                    example = "[\"ADMIN_REGISTRATION_CODE_CREATE\"]") Set<String> permissions,
            @Schema(description = "本次登录后是否需要提醒设置找回手机号/邮箱（USER/ORGANIZATION 未设置"
                    + "且未到下次提醒间隔时为 true；已设置或已关闭提醒时为 false；管理员恒为 false）",
                    example = "false") boolean recoveryReminder) {
    }
}
