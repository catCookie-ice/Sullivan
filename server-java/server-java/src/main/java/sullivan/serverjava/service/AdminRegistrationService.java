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

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;

@Service
public class AdminRegistrationService {
    private static final String CODE_PERMISSION = "ADMIN_REGISTRATION_CODE_CREATE";
    /** Redis 中注册码的键前缀；value 为 JSON：{"roleId":"...","expiresAt":"...","createdByAdminId":"..."}。 */
    private static final String REDIS_CODE_KEY_PREFIX = "admin:regcode:";
    private final AdminAccountMapper adminAccountMapper;
    private final AdminRoleMapper adminRoleMapper;
    private final AdminAccountRoleMapper adminAccountRoleMapper;
    private final AdminRegistrationCodeMapper registrationCodeMapper;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminRegistrationService(AdminAccountMapper adminAccountMapper, AdminRoleMapper adminRoleMapper,
                                    AdminAccountRoleMapper adminAccountRoleMapper,
                                    AdminRegistrationCodeMapper registrationCodeMapper,
                                    StringRedisTemplate redisTemplate,
                                    PasswordEncoder passwordEncoder) {
        this.adminAccountMapper = adminAccountMapper;
        this.adminRoleMapper = adminRoleMapper;
        this.adminAccountRoleMapper = adminAccountRoleMapper;
        this.registrationCodeMapper = registrationCodeMapper;
        this.redisTemplate = redisTemplate;
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
        String codeHash = Hashing.sha256(rawCode);
        AdminRegistrationCode code = new AdminRegistrationCode();
        code.setId(IdGenerator.next());
        code.setCodeHash(codeHash);
        code.setRoleId(initialRole.getId());
        code.setStatus("ACTIVE");
        code.setExpiresAt(request.expiresAt());
        code.setCreatedByAdminId(actor.id());
        code.setVersion(0L);
        registrationCodeMapper.insert(code);

        // Redis 双写：key=admin:regcode:<hash>，TTL=距过期时间的秒数（DB 仍为持久审计记录）。
        writeRedisCode(codeHash, initialRole.getId(), request.expiresAt(), actor.id());
        return new AdminCodeResponse(rawCode, initialRole.getRoleCode(), request.expiresAt());
    }

    /** 把注册码写入 Redis 并设置 TTL（到过期时间为止）；已过期则直接不写。 */
    private void writeRedisCode(String codeHash, String roleId, LocalDateTime expiresAt, String createdByAdminId) {
        try {
            long ttl = Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
            if (ttl <= 0) return;
            String value = "{\"roleId\":\"" + roleId + "\",\"expiresAt\":\""
                    + expiresAt + "\",\"createdByAdminId\":\"" + createdByAdminId + "\"}";
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            ops.set(REDIS_CODE_KEY_PREFIX + codeHash, value, Duration.ofSeconds(ttl));
        } catch (RuntimeException ignored) {
            // Redis 不可用（如未启动）时静默失败，注册码仍以 DB 为准（register 会回退 DB 校验）。
        }
    }

    @Transactional
    public RegistrationResponse register(AdminRegisterRequest request) {
        if (adminAccountMapper.selectCount(Wrappers.<AdminAccount>lambdaQuery()
                .eq(AdminAccount::getLoginName, request.loginName())) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "管理员登录名已存在");
        }

        String codeHash = Hashing.sha256(request.registrationCode());
        String adminId = IdGenerator.next();

        // 1) 优先走 Redis：读出并校验注册码（不销毁），随后原子 DEL 核销。
        //    Redis 无此 key 或不可用时回退到 DB 乐观锁 claim。
        RedisCodeData redisData = readRedisCode(codeHash);
        AdminRole role;
        String createdByAdminId;
        if (redisData != null) {
            // Redis value 中的 roleId 即角色 ID（createCode 时写入），按 ID 查并校验 ACTIVE。
            role = adminRoleMapper.selectById(redisData.roleId());
            if (role == null || !"ACTIVE".equals(role.getStatus())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "注册码对应角色不可用");
            }
            createdByAdminId = redisData.createdByAdminId();
            boolean deleted = Boolean.TRUE.equals(redisTemplate.delete(REDIS_CODE_KEY_PREFIX + codeHash));
            if (!deleted) {
                // 并发下被其他请求抢先核销 → 回退 DB 乐观锁（会因版本不匹配而拒绝）。
                ClaimResult claim = claimFromDb(codeHash, adminId, role.getId(), createdByAdminId);
                role = claim.role();
                createdByAdminId = claim.createdByAdminId();
            } else {
                markDbCodeUsed(codeHash, adminId);
            }
        } else {
            ClaimResult claim = claimFromDb(codeHash, adminId, null, null);
            role = claim.role();
            createdByAdminId = claim.createdByAdminId();
        }
        return finishRegister(adminId, request, role, createdByAdminId);
    }

    /** Redis 中的注册码数据（读自 value；roleId 为角色 ID，createdByAdminId 可能为 null）。 */
    private record RedisCodeData(String roleId, String createdByAdminId) {
    }

    /** 从 Redis 读取注册码数据；无此 key / 不可用 / 解析失败返回 null。 */
    private RedisCodeData readRedisCode(String codeHash) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_CODE_KEY_PREFIX + codeHash);
            if (json == null) return null;
            return new RedisCodeData(extract(json, "roleId"), extract(json, "createdByAdminId"));
        } catch (RuntimeException ignored) {
            return null; // Redis 不可用 → 回退 DB。
        }
    }

    /** 从 Redis 的 JSON value 中按字段名提取字符串值（不引入 JSON 库，值均为纯 ASCII 无引号）。 */
    private String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    /**
     * 回退路径：按 DB 注册码（ACTIVE 且未过期）走乐观锁 claim，标记 USED 并返回角色。
     * 命中 Redis 但 DEL 未命中（并发被抢）时也走这里，传入 knownRoleId 以便复用已查到的角色。
     *
     * @return null 表示注册码不存在/已过期/并发冲突（调用方抛 400/409）。
     */
    private ClaimResult claimFromDb(String codeHash, String adminId, String knownRoleId, String fallbackCreatedBy) {
        AdminRegistrationCode code = registrationCodeMapper.selectOne(
                Wrappers.<AdminRegistrationCode>lambdaQuery()
                        .eq(AdminRegistrationCode::getCodeHash, codeHash)
                        .eq(AdminRegistrationCode::getStatus, "ACTIVE"));
        if (code == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "注册码无效、已使用或已过期");
        }
        if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(LocalDateTime.now())) {
            markDbCodeExpired(code);
            throw new ApiException(HttpStatus.BAD_REQUEST, "注册码已过期");
        }
        AdminRole role = knownRoleId != null
                ? adminRoleMapper.selectById(knownRoleId)
                : adminRoleMapper.selectById(code.getRoleId());
        if (role == null || !"ACTIVE".equals(role.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "注册码对应角色不可用");
        }
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
        String createdBy = fallbackCreatedBy != null ? fallbackCreatedBy : code.getCreatedByAdminId();
        return new ClaimResult(role, createdBy);
    }

    private record ClaimResult(AdminRole role, String createdByAdminId) {
    }

    /** 创建管理员账号与角色关联（注册码已在调用方核销）。 */
    private RegistrationResponse finishRegister(String adminId, AdminRegisterRequest request, AdminRole role,
                                                String createdByAdminId) {
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
        accountRole.setGrantedByAdminId(createdByAdminId);
        adminAccountRoleMapper.insert(accountRole);
        return new RegistrationResponse(adminId, "ADMIN", "ENABLED", Set.of(role.getRoleCode()));
    }

    /** Redis 路径核销后，回填 DB 审计字段：标记 USED、记录使用管理员与时间。 */
    private void markDbCodeUsed(String codeHash, String usedByAdminId) {
        // 用显式列名（而非 lambda）构造条件，避免 MyBatis-Plus lambda 缓存解析在测试环境不可用。
        UpdateWrapper<AdminRegistrationCode> update = new UpdateWrapper<>();
        update.set("status", "USED")
                .set("used_by_admin_id", usedByAdminId)
                .set("used_at", LocalDateTime.now())
                .setSql("version = version + 1")
                .eq("code_hash", codeHash)
                .eq("status", "ACTIVE");
        registrationCodeMapper.update(null, update);
    }

    /** 过期处理：把仍在 ACTIVE 的过期注册码标记为 EXPIRED（满足“过期标识”字段）。 */
    private void markDbCodeExpired(AdminRegistrationCode code) {
        registrationCodeMapper.update(null, Wrappers.<AdminRegistrationCode>lambdaUpdate()
                .set(AdminRegistrationCode::getStatus, "EXPIRED")
                .setSql("version = version + 1")
                .eq(AdminRegistrationCode::getId, code.getId())
                .eq(AdminRegistrationCode::getStatus, "ACTIVE"));
    }
}
