package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.Hashing;
import sullivan.serverjava.common.RandomCode;
import sullivan.serverjava.domain.QrLoginTicket;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.QrLoginTicketMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.web.QrDtos.LoginTicketCreateResponse;
import sullivan.serverjava.web.QrDtos.LoginTicketScanResponse;
import sullivan.serverjava.web.QrDtos.LoginTicketStatusResponse;
import sullivan.serverjava.web.QrDtos.ProtecteeCandidate;
import sullivan.serverjava.web.RegistrationDtos.TokenResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 扫码授权登录（功能 B）：受护人**未登录**时在登录页出示登录码，监护人扫码、选中一个自己名下的受护人，
 * 出示码的那台设备即登录为该受护人。
 *
 * <p>状态机 {@code PENDING → SCANNED → CONFIRMED → CONSUMED}，与 docs/final/02 §4 的绑定状态机并列。</p>
 *
 * <p>这套流程真正的授权点是**监护人的 confirm**，其余都是防误用：</p>
 * <ul>
 *   <li>confirm 时服务端**重新校验**该监护人与该受护人存在 ACTIVE 绑定，绝不信任客户端传来的候选；</li>
 *   <li>令牌**不出现在 GET 轮询响应里**，只经由一次性的 exchange 下发（GET 带凭据出网是反模式，会进日志）；</li>
 *   <li>exchange 必须同时出示设备本地保存的 clientSecret 与轮询拿到的 exchangeCode；</li>
 *   <li>核销是一条带全部前置条件与乐观锁的 UPDATE，等价于 CAS。</li>
 * </ul>
 *
 * <p>本流程**不引入 Redis**：票据行本身就是唯一权威，再加一层缓存只会带来双写不一致
 * （管理员注册码当初就是为此才需要一条 DB 回退路径）。原子性由单条 UPDATE 的
 * {@code WHERE status=... AND version=...} 保证。</p>
 *
 * <p>过期一律**不在读路径上顺手写库**，只报状态、由定时任务清理：读路径上加 @Transactional 会让
 * 「标记过期后抛异常」把标记一起回滚，不加事务又要让每个读接口都产生写流量。</p>
 */
@Service
public class QrLoginService {
    /** 票据 16 字节 = 128 bit；只进二维码，不需要人读，取满熵。 */
    private static final int TICKET_BYTES = 16;
    /** 兑换凭据 24 字节；与 clientSecret 一起构成兑换所需的两项证据。 */
    private static final int EXCHANGE_CODE_BYTES = 24;
    /**
     * CONFIRMED 之后的宽限上限：确认后不再受 2 分钟 TTL 约束（否则出示方还没轮询到就白扫了），
     * 但也不能真的永不过期，定时任务按这个上限清理。
     */
    private static final long CONFIRMED_GRACE_SECONDS = 1800;

    private final QrLoginTicketMapper ticketMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final BindingService bindingService;
    private final AuthService authService;
    private final RoleGuard roleGuard;
    private final long ttlSeconds;

    public QrLoginService(QrLoginTicketMapper ticketMapper, UserAccountMapper userAccountMapper,
                          UserProfileMapper userProfileMapper, BindingService bindingService,
                          AuthService authService, RoleGuard roleGuard,
                          @Value("${app.qr.login-ticket-ttl-seconds:120}") long ttlSeconds) {
        this.ticketMapper = ticketMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.bindingService = bindingService;
        this.authService = authService;
        this.roleGuard = roleGuard;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 公开接口：出示设备创建登录票据。
     *
     * <p>设备本地生成 clientSecret（128 bit），只把 SHA-256 上传；明文留在设备上，
     * 兑换令牌时再出示，服务端据此确认「来换令牌的就是当初出示二维码的那台设备」。</p>
     */
    @Transactional
    public LoginTicketCreateResponse create(String secretHash) {
        QrLoginTicket ticket = new QrLoginTicket();
        ticket.setTicket(RandomCode.opaque(TICKET_BYTES));
        ticket.setStatus("PENDING");
        ticket.setSecretHash(secretHash);
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        ticket.setVersion(0L);
        try {
            ticketMapper.insert(ticket);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "登录码签发冲突，请重试");
        }
        return new LoginTicketCreateResponse(ticket.getTicket(), ticket.getExpiresAt(), ttlSeconds);
    }

    /** 公开接口：出示设备轮询。只返回状态，**绝不返回令牌**。 */
    public LoginTicketStatusResponse status(String ticket) {
        QrLoginTicket row = load(ticket);
        if ("CONSUMED".equals(row.getStatus())) {
            // 一次性：用过即当作不存在，免得泄露「这个票据曾经存在」。
            throw new ApiException(HttpStatus.NOT_FOUND, "登录码已使用或不存在");
        }
        if (isExpired(row)) {
            return new LoginTicketStatusResponse("EXPIRED", null, null, null, 0);
        }
        if (!"CONFIRMED".equals(row.getStatus())) {
            return new LoginTicketStatusResponse(row.getStatus(), null, null, null, remaining(row));
        }
        return new LoginTicketStatusResponse("CONFIRMED", row.getExchangeCode(),
                displayName(row.getProtecteeUid()), row.getProtecteeUid(), 0);
    }

    /** 监护人扫码：标记 SCANNED 并返回自己名下可授权的受护人候选。他人抢扫同一票据 409。 */
    public LoginTicketScanResponse scan(String guardianId, String ticket) {
        roleGuard.requireEnabledGuardian(guardianId);
        QrLoginTicket row = load(ticket);
        switch (row.getStatus()) {
            case "PENDING" -> {
                if (isExpired(row)) {
                    throw new ApiException(HttpStatus.GONE, "登录码已过期，请让受护人刷新");
                }
                int updated = ticketMapper.update(null, Wrappers.<QrLoginTicket>lambdaUpdate()
                        .set(QrLoginTicket::getStatus, "SCANNED")
                        .set(QrLoginTicket::getGuardianUid, guardianId)
                        .set(QrLoginTicket::getScannedAt, LocalDateTime.now())
                        .setSql("version = version + 1")
                        .eq(QrLoginTicket::getTicket, ticket)
                        .eq(QrLoginTicket::getStatus, "PENDING"));
                if (updated == 0) {
                    // 并发下被抢先：重读一次判断是谁抢的——同一监护人重扫是幂等的。
                    row = load(ticket);
                    if (!Objects.equals(guardianId, row.getGuardianUid())) {
                        throw new ApiException(HttpStatus.CONFLICT, "该登录码已被其他监护人扫描");
                    }
                    if (!"SCANNED".equals(row.getStatus())) {
                        throw new ApiException(HttpStatus.CONFLICT, "登录码状态已变更，请让受护人刷新");
                    }
                }
            }
            case "SCANNED" -> {
                if (!Objects.equals(guardianId, row.getGuardianUid())) {
                    throw new ApiException(HttpStatus.CONFLICT, "该登录码已被其他监护人扫描");
                }
                if (isExpired(row)) {
                    throw new ApiException(HttpStatus.GONE, "登录码已过期，请让受护人刷新");
                }
            }
            case "CONFIRMED" -> throw new ApiException(HttpStatus.CONFLICT, "该登录码已完成授权");
            default -> throw new ApiException(HttpStatus.GONE, "登录码已失效，请让受护人刷新");
        }
        return new LoginTicketScanResponse("SCANNED", candidates(guardianId));
    }

    /** 监护人确认：**服务端重新校验 ACTIVE 绑定**后生成兑换凭据，票据转为 CONFIRMED。 */
    @Transactional
    public LoginTicketStatusResponse confirm(String guardianId, String ticket, String protecteeId) {
        roleGuard.requireEnabledGuardian(guardianId);
        QrLoginTicket row = load(ticket);
        if ("CONFIRMED".equals(row.getStatus())) {
            // 同一监护人、同一受护人重复确认视为幂等，返回同一份凭据。
            if (Objects.equals(guardianId, row.getGuardianUid())
                    && Objects.equals(protecteeId, row.getProtecteeUid())) {
                return new LoginTicketStatusResponse("CONFIRMED", row.getExchangeCode(),
                        displayName(protecteeId), protecteeId, 0);
            }
            throw new ApiException(HttpStatus.CONFLICT, "该登录码已完成授权");
        }
        if (!"SCANNED".equals(row.getStatus())) {
            throw "PENDING".equals(row.getStatus())
                    ? new ApiException(HttpStatus.CONFLICT, "请先扫描该登录码")
                    : new ApiException(HttpStatus.GONE, "登录码已失效，请让受护人刷新");
        }
        if (!Objects.equals(guardianId, row.getGuardianUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该登录码由其他监护人扫描");
        }
        if (isExpired(row)) {
            throw new ApiException(HttpStatus.GONE, "登录码已过期，请让受护人刷新");
        }
        // 真授权点：只认服务端自己查出来的 ACTIVE 绑定，客户端传来的候选一律当不可信输入。
        if (!bindingService.hasActiveGuardianship(guardianId, protecteeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "你不是该受护人的监护人，无法授权登录");
        }
        roleGuard.requireEnabledProtectee(protecteeId);

        String exchangeCode = RandomCode.opaque(EXCHANGE_CODE_BYTES);
        int updated = ticketMapper.update(null, Wrappers.<QrLoginTicket>lambdaUpdate()
                .set(QrLoginTicket::getStatus, "CONFIRMED")
                .set(QrLoginTicket::getProtecteeUid, protecteeId)
                .set(QrLoginTicket::getExchangeCode, exchangeCode)
                .set(QrLoginTicket::getConfirmedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(QrLoginTicket::getTicket, ticket)
                .eq(QrLoginTicket::getStatus, "SCANNED")
                .eq(QrLoginTicket::getGuardianUid, guardianId));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "登录码状态已变更，请重新扫描");
        }
        return new LoginTicketStatusResponse("CONFIRMED", exchangeCode, displayName(protecteeId),
                protecteeId, 0);
    }

    /**
     * 公开接口：出示设备用 clientSecret + exchangeCode 兑换令牌，一次性消费。
     *
     * <p>兑换时重新校验绑定仍然 ACTIVE、受护人仍然 ENABLED —— 从 confirm 到 exchange 之间
     * 监护关系可能已被解除，不能拿旧授权换新令牌。</p>
     */
    @Transactional
    public TokenResponse exchange(String ticket, String clientSecret, String exchangeCode) {
        QrLoginTicket row = load(ticket);
        if ("CONSUMED".equals(row.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "登录码已使用或不存在");
        }
        if (!"CONFIRMED".equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "登录尚未获得授权");
        }
        if (!Objects.equals(Hashing.sha256(clientSecret), row.getSecretHash())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "出示设备校验失败");
        }
        if (!Objects.equals(exchangeCode, row.getExchangeCode())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "兑换凭据不正确");
        }
        if (!bindingService.hasActiveGuardianship(row.getGuardianUid(), row.getProtecteeUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "监护关系已解除，授权失效");
        }
        roleGuard.requireEnabledProtectee(row.getProtecteeUid());

        // 原子核销：一条 UPDATE 带齐全部前置条件与乐观锁版本，等价于 CAS。
        int consumed = ticketMapper.update(null, Wrappers.<QrLoginTicket>lambdaUpdate()
                .set(QrLoginTicket::getStatus, "CONSUMED")
                .set(QrLoginTicket::getConsumedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(QrLoginTicket::getTicket, ticket)
                .eq(QrLoginTicket::getStatus, "CONFIRMED")
                .eq(QrLoginTicket::getSecretHash, row.getSecretHash())
                .eq(QrLoginTicket::getVersion, row.getVersion()));
        if (consumed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "登录码已被使用");
        }
        // 复用账密登录的那套角色解析，避免两处逻辑各自漂移。
        return authService.userToken(userAccountMapper.selectById(row.getProtecteeUid()));
    }

    /** 定时任务：PENDING/SCANNED 超过 TTL、CONFIRMED 超过宽限上限的票据置为 EXPIRED。 */
    @Scheduled(cron = "0 11 * * * *")
    @Transactional
    public void expireExpiredTickets() {
        LocalDateTime now = LocalDateTime.now();
        ticketMapper.update(null, Wrappers.<QrLoginTicket>lambdaUpdate()
                .set(QrLoginTicket::getStatus, "EXPIRED")
                .setSql("version = version + 1")
                .in(QrLoginTicket::getStatus, "PENDING", "SCANNED")
                .le(QrLoginTicket::getExpiresAt, now));
        ticketMapper.update(null, Wrappers.<QrLoginTicket>lambdaUpdate()
                .set(QrLoginTicket::getStatus, "EXPIRED")
                .setSql("version = version + 1")
                .eq(QrLoginTicket::getStatus, "CONFIRMED")
                .le(QrLoginTicket::getConfirmedAt, now.minusSeconds(CONFIRMED_GRACE_SECONDS)));
    }

    /** 该监护人 ACTIVE 绑定的受护人候选（仅列 ENABLED 账号）。 */
    private List<ProtecteeCandidate> candidates(String guardianId) {
        List<ProtecteeCandidate> result = new ArrayList<>();
        for (String protecteeId : bindingService.activeProtecteeIds(guardianId)) {
            UserAccount account = userAccountMapper.selectById(protecteeId);
            if (account == null || !"ENABLED".equals(account.getStatus())) {
                continue;
            }
            result.add(new ProtecteeCandidate(protecteeId, displayName(protecteeId), account.getLoginName()));
        }
        return result;
    }

    private QrLoginTicket load(String ticket) {
        QrLoginTicket row = ticket == null || ticket.isBlank() ? null : ticketMapper.selectById(ticket);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "登录码不存在或已失效");
        }
        return row;
    }

    private boolean isExpired(QrLoginTicket row) {
        return row.getExpiresAt() != null && !row.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private long remaining(QrLoginTicket row) {
        return row.getExpiresAt() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), row.getExpiresAt()).getSeconds());
    }

    private String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, userId));
        if (profile != null) {
            return profile.getDisplayName();
        }
        UserAccount account = userAccountMapper.selectById(userId);
        return account == null ? null : account.getLoginName();
    }
}
