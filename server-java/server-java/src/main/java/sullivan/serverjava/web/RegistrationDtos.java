package sullivan.serverjava.web;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

public final class RegistrationDtos {
    private RegistrationDtos() {
    }

    public record UserRegisterRequest(
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName,
            @Pattern(regexp = "GUARDIAN|VOLUNTEER") String role) {
    }

    public record ProtecteeRegisterRequest(
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName,
            @Size(max = 500) String emergencyNote) {
    }

    public record BindingRequest(@NotBlank String protecteeId) {
    }

    public record BindingResponse(String id, String status, String guardianId, String protecteeId,
                                  boolean permanent, boolean primary) {
    }

    public record OrganizationRegisterRequest(
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 160) String legalName,
            @NotBlank @Size(max = 160) String publicName) {
    }

    public record AdminRegisterRequest(
            @NotBlank @Size(min = 4, max = 64) String loginName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank String registrationCode) {
    }

    public record AdminCodeCreateRequest(
            @NotBlank String initialRoleCode,
            @Future LocalDateTime expiresAt) {
    }

    public record LoginRequest(
            @Pattern(regexp = "USER|ORGANIZATION|ADMIN") String principalType,
            @NotBlank String loginName,
            @NotBlank String password) {
    }

    public record RegistrationResponse(String id, String principalType, String status, Set<String> roles) {
    }

    public record AdminCodeResponse(String code, String initialRoleCode, LocalDateTime expiresAt) {
    }

    public record TokenResponse(String accessToken, String principalId, String principalType,
                                Set<String> roles, Set<String> permissions) {
    }
}
