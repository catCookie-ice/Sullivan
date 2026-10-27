package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.RegistrationDtos.OrganizationRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.ProtecteeRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;
import sullivan.serverjava.web.RegistrationDtos.UserRegisterRequest;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class RegistrationService {
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserRoleMapper userRoleMapper;
    private final GuardianBindingMapper guardianBindingMapper;
    private final OrganizationMapper organizationMapper;
    private final RecoveryService recoveryService;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserAccountMapper userAccountMapper, UserProfileMapper userProfileMapper,
                               UserRoleMapper userRoleMapper, GuardianBindingMapper guardianBindingMapper,
                               OrganizationMapper organizationMapper, RecoveryService recoveryService,
                               PasswordEncoder passwordEncoder) {
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.userRoleMapper = userRoleMapper;
        this.guardianBindingMapper = guardianBindingMapper;
        this.organizationMapper = organizationMapper;
        this.recoveryService = recoveryService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistrationResponse registerUser(UserRegisterRequest request) {
        // 不能写成 Set.of("GUARDIAN","VOLUNTEER").contains(request.role())：Set.of 的不可变集合
        // 在 contains(null) 时抛 NPE，而 role 上的 @Pattern 不校验 null，于是公开接口上省略 role
        // 会返回 500（内部错误）而不是 400（参数错）。
        if (!"GUARDIAN".equals(request.role()) && !"VOLUNTEER".equals(request.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "自然人只能自主注册为监护人或志愿者");
        }
        assertUserLoginAvailable(request.loginName());
        UserAccount account = createUserAccount(request.loginName(), request.password(), request.role());
        createProfile(account.getId(), request.displayName(), null);
        createUserRole(account.getId(), request.role(), account.getId());
        recoveryService.setContacts("USER", account.getId(), request.recoveryPhone(), request.recoveryEmail());
        return new RegistrationResponse(account.getId(), "USER", "ENABLED", Set.of(request.role()));
    }

    @Transactional
    public RegistrationResponse registerProtectee(String guardianId, ProtecteeRegisterRequest request) {
        requireActiveRole(guardianId, "GUARDIAN");
        assertUserLoginAvailable(request.loginName());

        UserAccount protectee = createUserAccount(request.loginName(), request.password(), "PROTECTEE");
        createProfile(protectee.getId(), request.displayName(), request.emergencyNote());
        createUserRole(protectee.getId(), "PROTECTEE", guardianId);

        GuardianBinding binding = new GuardianBinding();
        binding.setId(IdGenerator.next());
        binding.setStatus("ACTIVE");
        binding.setGuardianUid(guardianId);
        binding.setProtecteeUid(protectee.getId());
        binding.setIsPrimary(true);
        binding.setPermanentFlag(true);
        binding.setRequestedBy(guardianId);
        // ck_guardian_binding_confirmed 要求 ACTIVE 行必须有 confirmed_at；
        // 监护人代受护人注册即视为受护人当场确认。
        binding.setConfirmedAt(LocalDateTime.now());
        guardianBindingMapper.insert(binding);

        return new RegistrationResponse(protectee.getId(), "USER", "ENABLED", Set.of("PROTECTEE"));
    }

    @Transactional
    public RegistrationResponse registerOrganization(OrganizationRegisterRequest request) {
        Organization existing = organizationMapper.selectOne(Wrappers.<Organization>lambdaQuery()
                .eq(Organization::getLoginName, request.loginName()));
        if (existing != null) {
            throw new ApiException(HttpStatus.CONFLICT, "机构登录名已存在");
        }
        Organization organization = new Organization();
        organization.setId(IdGenerator.next());
        organization.setStatus("PENDING");
        organization.setLoginName(request.loginName());
        organization.setPasswordHash(passwordEncoder.encode(request.password()));
        organization.setLegalName(request.legalName());
        organization.setPublicName(request.publicName());
        try {
            organizationMapper.insert(organization);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "机构登录名已存在");
        }
        recoveryService.setContacts("ORGANIZATION", organization.getId(),
                request.recoveryPhone(), request.recoveryEmail());
        return new RegistrationResponse(organization.getId(), "ORGANIZATION", "PENDING", Set.of("ORGANIZATION"));
    }

    public void requireActiveRole(String userId, String roleCode) {
        Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getStatus, "ACTIVE"));
        if (count == 0) {
            UserAccount account = userAccountMapper.selectById(userId);
            if (account != null && roleCode.equals(account.getRoleCode())) {
                return;
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "当前用户不具备" + roleCode + "角色");
        }
    }

    private UserAccount createUserAccount(String loginName, String password, String primaryRole) {
        UserAccount account = new UserAccount();
        account.setId(IdGenerator.next());
        account.setStatus("ENABLED");
        account.setLoginName(loginName);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setRoleCode(primaryRole);
        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "用户登录名已存在");
        }
        return account;
    }

    private void createProfile(String userId, String displayName, String emergencyNote) {
        UserProfile profile = new UserProfile();
        profile.setId(IdGenerator.next());
        profile.setStatus("ACTIVE");
        profile.setUserId(userId);
        profile.setDisplayName(displayName);
        profile.setEmergencyNote(emergencyNote);
        userProfileMapper.insert(profile);
    }

    private void createUserRole(String userId, String roleCode, String creatorId) {
        UserRole role = new UserRole();
        role.setId(IdGenerator.next());
        role.setUserId(userId);
        role.setRoleCode(roleCode);
        role.setStatus("ACTIVE");
        role.setCreatedByUserId(creatorId);
        userRoleMapper.insert(role);
    }

    private void assertUserLoginAvailable(String loginName) {
        Long count = userAccountMapper.selectCount(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getLoginName, loginName));
        if (count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "用户登录名已存在");
        }
    }
}
