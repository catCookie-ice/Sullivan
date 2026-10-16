package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.RandomCode;
import sullivan.serverjava.domain.ProtecteeBindCode;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.ProtecteeBindCodeMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.web.QrDtos.BindCodeResponse;
import sullivan.serverjava.web.QrDtos.BindScanResponse;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 扫码绑定（功能 A）：受护人**登录后**出示绑定码，监护人扫码即生效。
 *
 * <p>绑定码只解决「受护人是谁」——监护人的身份一律取自 Bearer 令牌。若允许从二维码或
 * 请求体中指定监护人，任何人都能把受害者写成自己的受护人。</p>
 *
 * <p>绑定码在 TTL 内可被**不同**监护人分别扫到并各自建立一条关系（「扫码加好友」的字面语义）。
 * 缓解：TTL 只有两分钟、每位受护人同时只有一张有效码，且受护人可随时在绑定列表里解除。</p>
 */
@Service
public class QrBindCodeService {
    /** 短码长度：30 字符表下约 59 bit；见 {@link RandomCode#dictatable(int)}。 */
    private static final int CODE_LENGTH = 12;

    private final ProtecteeBindCodeMapper bindCodeMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final BindingService bindingService;
    private final RoleGuard roleGuard;
    private final long ttlSeconds;

    public QrBindCodeService(ProtecteeBindCodeMapper bindCodeMapper, UserAccountMapper userAccountMapper,
                             UserProfileMapper userProfileMapper, BindingService bindingService,
                             RoleGuard roleGuard,
                             @Value("${app.qr.bind-code-ttl-seconds:120}") long ttlSeconds) {
        this.bindCodeMapper = bindCodeMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.bindingService = bindingService;
        this.roleGuard = roleGuard;
        this.ttlSeconds = ttlSeconds;
    }

    /** 受护人本人签发新绑定码：作废该受护人此前所有 ACTIVE 码，保证同时只有一张有效码。 */
    @Transactional
    public BindCodeResponse issueCode(String protecteeId) {
        roleGuard.requireEnabledProtectee(protecteeId);
        revokeActiveCodes(protecteeId);

        ProtecteeBindCode bindCode = new ProtecteeBindCode();
        bindCode.setCode(RandomCode.dictatable(CODE_LENGTH));
        bindCode.setProtecteeUid(protecteeId);
        bindCode.setStatus("ACTIVE");
        bindCode.setCreatedAt(LocalDateTime.now());
        bindCode.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        bindCode.setVersion(0L);
        try {
            bindCodeMapper.insert(bindCode);
        } catch (DuplicateKeyException exception) {
            // 唯一键 active_code_key 冲突：同一受护人并发签发。让客户端刷新重试即可。
            throw new ApiException(HttpStatus.CONFLICT, "绑定码签发冲突，请刷新重试");
        }
        return toResponse(bindCode, displayName(protecteeId));
    }

    /** 受护人本人取当前有效码；无有效码返回 null（控制器转 204）。 */
    public BindCodeResponse currentCode(String protecteeId) {
        roleGuard.requireEnabledProtectee(protecteeId);
        ProtecteeBindCode bindCode = bindCodeMapper.selectOne(Wrappers.<ProtecteeBindCode>lambdaQuery()
                .eq(ProtecteeBindCode::getProtecteeUid, protecteeId)
                .eq(ProtecteeBindCode::getStatus, "ACTIVE")
                .gt(ProtecteeBindCode::getExpiresAt, LocalDateTime.now()));
        return bindCode == null ? null : toResponse(bindCode, displayName(protecteeId));
    }

    /**
     * 监护人扫码：即时建立 ACTIVE 绑定（受护人发起 + 监护人扫码确认），重复扫幂等。
     *
     * <p>与参考 demo 的一处有意偏离：demo 会把既有的 PENDING 行升级为 ACTIVE。本项目不做这种升级
     * —— 若该对关系已存在由监护人发起的 PENDING，{@link BindingService#bindViaScan} 抛 409，
     * 因为 docs/final/02 §4.2 规定只有接收方能 ACCEPT，扫码不能替对方确认。</p>
     *
     * <p>本方法自身不加事务：真正的写入在 {@code BindingService.bindViaScan} 里自成事务，而这里
     * 只读校验；加了外层事务反而会让「过期 → 抛 410」把过期标记一起回滚掉。</p>
     */
    public BindScanResponse scan(String guardianId, String code) {
        roleGuard.requireEnabledGuardian(guardianId);
        ProtecteeBindCode bindCode = bindCodeMapper.selectById(code);
        if (bindCode == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "绑定码不存在，请确认输入的绑定码");
        }
        if (!"ACTIVE".equals(bindCode.getStatus())) {
            throw new ApiException(HttpStatus.GONE, "绑定码已失效，请让受护人刷新后重试");
        }
        if (bindCode.getExpiresAt() == null || !bindCode.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.GONE, "绑定码已过期，请让受护人刷新后重试");
        }

        String protecteeId = bindCode.getProtecteeUid();
        BindingService.ScanBindResult result = bindingService.bindViaScan(protecteeId, guardianId);
        String protecteeName = displayName(protecteeId);
        String message = result.alreadyBound()
                ? "你已经是 " + protecteeName + " 的监护人"
                : "已绑定 " + protecteeName;
        return new BindScanResponse(result.binding().id(), result.binding().status(), guardianId,
                protecteeId, protecteeName, result.alreadyBound(), message);
    }

    /** 定时任务：把超过 TTL 仍是 ACTIVE 的绑定码置为 EXPIRED，避免长期占用唯一键。 */
    @Scheduled(cron = "0 7 * * * *")
    @Transactional
    public void expireExpiredCodes() {
        bindCodeMapper.update(null, Wrappers.<ProtecteeBindCode>lambdaUpdate()
                .set(ProtecteeBindCode::getStatus, "EXPIRED")
                .setSql("version = version + 1")
                .eq(ProtecteeBindCode::getStatus, "ACTIVE")
                .le(ProtecteeBindCode::getExpiresAt, LocalDateTime.now()));
    }

    private void revokeActiveCodes(String protecteeId) {
        bindCodeMapper.update(null, Wrappers.<ProtecteeBindCode>lambdaUpdate()
                .set(ProtecteeBindCode::getStatus, "REVOKED")
                .set(ProtecteeBindCode::getRevokedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(ProtecteeBindCode::getProtecteeUid, protecteeId)
                .eq(ProtecteeBindCode::getStatus, "ACTIVE"));
    }

    private BindCodeResponse toResponse(ProtecteeBindCode bindCode, String protecteeName) {
        long remaining = bindCode.getExpiresAt() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), bindCode.getExpiresAt()).getSeconds());
        return new BindCodeResponse(bindCode.getCode(), bindCode.getProtecteeUid(), protecteeName,
                bindCode.getExpiresAt(), remaining);
    }

    private String displayName(String userId) {
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, userId));
        if (profile != null) {
            return profile.getDisplayName();
        }
        UserAccount account = userAccountMapper.selectById(userId);
        return account == null ? null : account.getLoginName();
    }
}
