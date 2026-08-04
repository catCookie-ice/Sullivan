package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.Hashing;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminRegistrationCode;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminRegistrationCodeMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeCreateRequest;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeResponse;
import sullivan.serverjava.web.RegistrationDtos.AdminRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;

@Service
public class AdminRegistrationService {
    private static final String CODE_PERMISSION = "ADMIN_REGISTRATION_CODE_CREATE";
    private final AdminAccountMapper adminAccountMapper;
    private final AdminRoleMapper adminRoleMapper;
    private final AdminAccountRoleMapper adminAccountRoleMapper;
    private final AdminRegistrationCodeMapper registrationCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminRegistrationService(AdminAccountMapper adminAccountMapper, AdminRoleMapper adminRoleMapper,
                                    AdminAccountRoleMapper adminAccountRoleMapper,
                                    AdminRegistrationCodeMapper registrationCodeMapper,
                                    PasswordEncoder passwordEncoder) {
        this.adminAccountMapper = adminAccountMapper;
        this.adminRoleMapper = adminRoleMapper;
        this.adminAccountRoleMapper = adminAccountRoleMapper;
        this.registrationCodeMapper = registrationCodeMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AdminCodeResponse createCode(AuthPrincipal actor, AdminCodeCreateRequest request) {
        if (!"ADMIN".equals(actor.principalType()) ||
                !(actor.hasRole("SUPER_ADMIN") || actor.hasRole("HR_ADMIN")) ||
                !actor.hasPermission(CODE_PERMISSION)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有最高管理员和HR管理员可以创建注册码");
        }
        if (!actor.hasRole("SUPER_ADMIN") && Set.of("SUPER_ADMIN", "HR_ADMIN").contains(request.initialRoleCode())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有最高管理员可以签发特权管理员注册码");
        }
        AdminRole initialRole = adminRoleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, request.initialRoleCode())
                .eq(AdminRole::getStatus, "ACTIVE"));
        if (initialRole == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "初始管理员角色不存在或已停用");
        }

        byte[] random = new byte[24];
        secureRandom.nextBytes(random);
        String rawCode = "SV-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        AdminRegistrationCode code = new AdminRegistrationCode();
        code.setId(IdGenerator.next());
        code.setCodeHash(Hashing.sha256(rawCode));
        code.setRoleId(initialRole.getId());
        code.setStatus("ACTIVE");
        code.setExpiresAt(request.expiresAt());
        code.setCreatedByAdminId(actor.id());
        code.setVersion(0L);
        registrationCodeMapper.insert(code);
        return new AdminCodeResponse(rawCode, initialRole.getRoleCode(), request.expiresAt());
    }

    @Transactional
    public RegistrationResponse register(AdminRegisterRequest request) {
        if (adminAccountMapper.selectCount(Wrappers.<AdminAccount>lambdaQuery()
                .eq(AdminAccount::getLoginName, request.loginName())) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "管理员登录名已存在");
        }

        AdminRegistrationCode code = registrationCodeMapper.selectOne(
                Wrappers.<AdminRegistrationCode>lambdaQuery()
                        .eq(AdminRegistrationCode::getCodeHash, Hashing.sha256(request.registrationCode()))
                        .eq(AdminRegistrationCode::getStatus, "ACTIVE"));
        if (code == null || code.getExpiresAt() != null && !code.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "注册码无效、已使用或已过期");
        }
        AdminRole role = adminRoleMapper.selectById(code.getRoleId());
        if (role == null || !"ACTIVE".equals(role.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "注册码对应角色不可用");
        }

        String adminId = IdGenerator.next();
        UpdateWrapper<AdminRegistrationCode> claim = new UpdateWrapper<>();
        claim.set("status", "USED")
                .set("used_by_admin_id", adminId)
                .set("used_at", LocalDateTime.now())
                .setSql("version = version + 1")
                .eq("id", code.getId())
                .eq("status", "ACTIVE")
                .eq("version", code.getVersion());
        int claimed = registrationCodeMapper.update(null, claim);
        if (claimed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "注册码已被其他注册请求使用");
        }

        AdminAccount admin = new AdminAccount();
        admin.setId(adminId);
        admin.setStatus("ENABLED");
        admin.setLoginName(request.loginName());
        admin.setPasswordHash(passwordEncoder.encode(request.password()));
        admin.setDisplayName(request.displayName());
        try {
            adminAccountMapper.insert(admin);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "管理员登录名已存在");
        }

        AdminAccountRole accountRole = new AdminAccountRole();
        accountRole.setId(IdGenerator.next());
        accountRole.setAdminId(adminId);
        accountRole.setRoleId(role.getId());
        accountRole.setStatus("ACTIVE");
        accountRole.setGrantedByAdminId(code.getCreatedByAdminId());
        adminAccountRoleMapper.insert(accountRole);
        return new RegistrationResponse(adminId, "ADMIN", "ENABLED", Set.of(role.getRoleCode()));
    }
}
