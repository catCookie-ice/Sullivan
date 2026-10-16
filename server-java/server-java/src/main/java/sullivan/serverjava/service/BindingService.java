package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.BindingDtos.BindingRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 监护绑定双向流程：监护人与受护人均可发起，接收方（非发起方）确认或拒绝。
 *
 * <p>方向由 {@code requested_by} 与 {@code guardian_uid}/protectee_uid 比较得出；
 * 状态机以 docs/final/02 §4 为唯一口径。永久绑定（permanent_flag=true）不可解除。</p>
 */
@Service
public class BindingService {
    private static final long EXPIRE_DAYS = 7;

    private final GuardianBindingMapper guardianBindingMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;

    public BindingService(GuardianBindingMapper guardianBindingMapper, UserAccountMapper userAccountMapper,
                          UserRoleMapper userRoleMapper) {
        this.guardianBindingMapper = guardianBindingMapper;
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** 扫码绑定的结果：{@code alreadyBound=true} 表示关系此前已生效，本次扫码是幂等重扫。 */
    public record ScanBindResult(BindingResponse binding, boolean alreadyBound) {
    }

    /** 当前用户作为发起方发起绑定：targetRole=PROTECTEE 时调用者须为监护人，反之亦然。 */
    @Transactional
    public BindingResponse requestBinding(String callerId, BindingRequest request) {
        String targetRole = request.targetRole();
        String requesterRole = "PROTECTEE".equals(targetRole) ? "GUARDIAN" : "PROTECTEE";
        requireActiveRole(callerId, requesterRole);

        UserAccount target = userAccountMapper.selectById(request.targetId());
        if (target == null || !hasRole(target.getId(), targetRole) || !"ENABLED".equals(target.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, targetRole + "不存在或不可绑定");
        }
        String guardianId = "PROTECTEE".equals(targetRole) ? callerId : target.getId();
        String protecteeId = "PROTECTEE".equals(targetRole) ? target.getId() : callerId;

        Long duplicate = guardianBindingMapper.selectCount(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getGuardianUid, guardianId)
                .eq(GuardianBinding::getProtecteeUid, protecteeId)
                .in(GuardianBinding::getStatus, "PENDING", "ACTIVE"));
        if (duplicate > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该监护关系已存在或正在等待确认");
        }

        GuardianBinding binding = new GuardianBinding();
        binding.setId(IdGenerator.next());
        binding.setStatus("PENDING");
        binding.setGuardianUid(guardianId);
        binding.setProtecteeUid(protecteeId);
        binding.setIsPrimary(false);
        binding.setPermanentFlag(false);
        binding.setRequestedBy(callerId);
        binding.setRequestedAt(LocalDateTime.now());
        binding.setExpiresAt(LocalDateTime.now().plusDays(EXPIRE_DAYS));
        guardianBindingMapper.insert(binding);
        return toBindingResponse(binding);
    }

    /**
     * 扫码绑定：受护人出示绑定码、监护人扫码后**即时生效**，记为「受护人发起 + 监护人扫码确认」。
     *
     * <p>落点完全在 docs/final/02 §4.2 既有转换内：受护人 {@code requestBinding} 建 PENDING，
     * 监护人作为接收方 {@code acceptBinding}，两步同事务，无需基线变更。之所以要一个外层事务，
     * 是因为两步之间失败会留下一条无人认领的 PENDING 行。</p>
     *
     * <p>幂等：同一监护人对同一受护人重复扫码返回既有 ACTIVE 关系（{@code alreadyBound=true}），
     * 而不是 409 —— 「扫码加好友」语义下重复扫是正常操作。但若已存在一条他人发起的 PENDING
     * （即受护人还没确认的、由监护人发起的申请），则 409：只有接收方能 ACCEPT，不能代对方确认。</p>
     */
    @Transactional
    public ScanBindResult bindViaScan(String protecteeId, String guardianId) {
        requireEnabledUser(protecteeId, "受护人");
        requireActiveRole(protecteeId, "PROTECTEE");

        GuardianBinding existing = guardianBindingMapper.selectOne(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getGuardianUid, guardianId)
                .eq(GuardianBinding::getProtecteeUid, protecteeId)
                .eq(GuardianBinding::getStatus, "ACTIVE"));
        if (existing != null) {
            return new ScanBindResult(toBindingResponse(existing), true);
        }
        Long pending = guardianBindingMapper.selectCount(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getGuardianUid, guardianId)
                .eq(GuardianBinding::getProtecteeUid, protecteeId)
                .eq(GuardianBinding::getStatus, "PENDING"));
        if (pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "已有待确认的绑定申请，请在绑定列表中处理后再扫码");
        }

        BindingResponse pendingBinding = requestBinding(protecteeId, new BindingRequest(guardianId, "GUARDIAN"));
        BindingResponse active = acceptBinding(guardianId, pendingBinding.id());
        if (active.primary()) {
            demoteScanBinding(active);
            // 回读一次：降级之后可能又被 promoteNextPrimary 提回主绑定，响应必须与库内一致。
            active = toBindingResponse(loadBinding(active.id()));
        }
        return new ScanBindResult(active, false);
    }

    /**
     * 显式固化「扫码建立的关系不是主监护」，不依赖 {@code acceptBinding} 内部「首条即主」的隐式判断。
     *
     * <p>受护人由监护人建号时已写入 permanent 主绑定，所以扫码路径本就不可能拿到 primary；
     * 写死可避免将来受护人自助注册（无既有主绑定）时静默变成主监护。若置为 false 后该受护人
     * 反而没有任何 ACTIVE 主绑定了（异常路径），按 confirmed_at 提升最早的一条，保持不变量。</p>
     */
    private void demoteScanBinding(BindingResponse created) {
        guardianBindingMapper.update(null, Wrappers.<GuardianBinding>lambdaUpdate()
                .set(GuardianBinding::getIsPrimary, false)
                .setSql("version = version + 1")
                .eq(GuardianBinding::getId, created.id())
                .eq(GuardianBinding::getIsPrimary, true));
        promoteNextPrimary(created.protecteeId());
    }

    /** 账号必须存在且 ENABLED。 */
    private void requireEnabledUser(String userId, String label) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, label + "不存在");
        }
        if (!"ENABLED".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, label + "账号不可用");
        }
    }

    /** 接收方（非发起方）确认绑定。首条生效关系自动成为主绑定。 */
    @Transactional
    public BindingResponse acceptBinding(String callerId, String bindingId) {
        GuardianBinding binding = loadBinding(bindingId);
        requireNonInitiator(callerId, binding);
        requirePending(binding);
        boolean hasPrimary = guardianBindingMapper.selectCount(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getProtecteeUid, binding.getProtecteeUid())
                .eq(GuardianBinding::getStatus, "ACTIVE")
                .eq(GuardianBinding::getIsPrimary, true)) > 0;
        int updated = guardianBindingMapper.update(null, Wrappers.<GuardianBinding>lambdaUpdate()
                .set(GuardianBinding::getStatus, "ACTIVE")
                .set(GuardianBinding::getIsPrimary, !hasPrimary)
                .set(GuardianBinding::getConfirmedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(GuardianBinding::getId, bindingId)
                .eq(GuardianBinding::getStatus, "PENDING"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定请求已处理");
        }
        binding.setStatus("ACTIVE");
        binding.setIsPrimary(!hasPrimary);
        return toBindingResponse(binding);
    }

    /** 接收方（非发起方）拒绝绑定。 */
    @Transactional
    public BindingResponse rejectBinding(String callerId, String bindingId, String reason) {
        GuardianBinding binding = loadBinding(bindingId);
        requireNonInitiator(callerId, binding);
        requirePending(binding);
        int updated = updateStatus(binding, "REJECTED", reason);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定请求已处理");
        }
        binding.setStatus("REJECTED");
        return toBindingResponse(binding);
    }

    /** 发起方在确认前取消绑定请求。 */
    @Transactional
    public BindingResponse cancelBinding(String callerId, String bindingId) {
        GuardianBinding binding = loadBinding(bindingId);
        if (!Objects.equals(callerId, binding.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有发起方可以取消绑定请求");
        }
        requirePending(binding);
        int updated = updateStatus(binding, "CANCELLED", null);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定请求已处理");
        }
        binding.setStatus("CANCELLED");
        return toBindingResponse(binding);
    }

    /** 解除生效关系。永久初始绑定不可解除；解除主绑定后按 confirmed_at 提升最早其余 ACTIVE 为主绑定。 */
    @Transactional
    public BindingResponse terminateBinding(String callerId, String bindingId, String reason) {
        GuardianBinding binding = loadBinding(bindingId);
        requireParticipant(callerId, binding);
        if (!"ACTIVE".equals(binding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定状态非生效");
        }
        if (Boolean.TRUE.equals(binding.getPermanentFlag())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "永久初始绑定不可解除");
        }
        boolean wasPrimary = Boolean.TRUE.equals(binding.getIsPrimary());
        int updated = updateStatus(binding, "TERMINATED", reason);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定状态已变更");
        }
        if (wasPrimary) {
            promoteNextPrimary(binding.getProtecteeUid());
        }
        binding.setStatus("TERMINATED");
        return toBindingResponse(binding);
    }

    /** 我的绑定与请求列表（作为监护人或受护人参与的所有关系）。 */
    public List<BindingResponse> listMyBindings(String callerId) {
        return guardianBindingMapper.selectList(Wrappers.<GuardianBinding>lambdaQuery()
                        .and(w -> w.eq(GuardianBinding::getGuardianUid, callerId)
                                .or().eq(GuardianBinding::getProtecteeUid, callerId))
                        .orderByDesc(GuardianBinding::getCreatedAt))
                .stream().map(this::toBindingResponse).toList();
    }

    /** 定时任务：将超过 7 天仍未处理的 PENDING 绑定置为 EXPIRED。 */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireExpiredBindings() {
        guardianBindingMapper.update(null, Wrappers.<GuardianBinding>lambdaUpdate()
                .set(GuardianBinding::getStatus, "EXPIRED")
                .setSql("version = version + 1")
                .eq(GuardianBinding::getStatus, "PENDING")
                .isNotNull(GuardianBinding::getExpiresAt)
                .le(GuardianBinding::getExpiresAt, LocalDateTime.now()));
    }

    /** 双方是否存在 ACTIVE 绑定（实时会话创建与消息中继复用）。 */
    public boolean hasActiveBinding(String uidA, String uidB) {
        return guardianBindingMapper.selectCount(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getStatus, "ACTIVE")
                .and(w -> w.eq(GuardianBinding::getGuardianUid, uidA)
                        .eq(GuardianBinding::getProtecteeUid, uidB)
                        .or()
                        .eq(GuardianBinding::getGuardianUid, uidB)
                        .eq(GuardianBinding::getProtecteeUid, uidA))) > 0;
    }

    /** 该监护人当前 ACTIVE 绑定的受护人 ID 列表（扫码登录的候选来源）。 */
    public List<String> activeProtecteeIds(String guardianId) {
        return guardianBindingMapper.selectList(Wrappers.<GuardianBinding>lambdaQuery()
                        .eq(GuardianBinding::getGuardianUid, guardianId)
                        .eq(GuardianBinding::getStatus, "ACTIVE")
                        .orderByAsc(GuardianBinding::getConfirmedAt)
                        .orderByAsc(GuardianBinding::getId))
                .stream().map(GuardianBinding::getProtecteeUid).toList();
    }

    /**
     * 方向固定的 ACTIVE 绑定判断：{@code guardianId} 必须是 {@code protecteeId} 的监护人。
     *
     * <p>与 {@link #hasActiveBinding} 的区别在于不复用「任一方向」语义 —— 扫码授权登录的真授权点，
     * 必须确认「这个监护人确实管着这个受护人」，而不是「两人之间存在某种关系」。</p>
     */
    public boolean hasActiveGuardianship(String guardianId, String protecteeId) {
        return guardianBindingMapper.selectCount(Wrappers.<GuardianBinding>lambdaQuery()
                .eq(GuardianBinding::getGuardianUid, guardianId)
                .eq(GuardianBinding::getProtecteeUid, protecteeId)
                .eq(GuardianBinding::getStatus, "ACTIVE")) > 0;
    }

    private GuardianBinding loadBinding(String bindingId) {
        GuardianBinding binding = guardianBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "绑定关系不存在");
        }
        return binding;
    }

    private void requireParticipant(String callerId, GuardianBinding binding) {
        if (!Objects.equals(callerId, binding.getGuardianUid())
                && !Objects.equals(callerId, binding.getProtecteeUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作该绑定关系");
        }
    }

    private void requireNonInitiator(String callerId, GuardianBinding binding) {
        requireParticipant(callerId, binding);
        if (Objects.equals(callerId, binding.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "发起方不能确认或拒绝自己发起的请求");
        }
    }

    private void requirePending(GuardianBinding binding) {
        if (!"PENDING".equals(binding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定请求已处理");
        }
        if (binding.getExpiresAt() != null && !binding.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "绑定请求已过期");
        }
    }

    private int updateStatus(GuardianBinding binding, String status, String note) {
        var update = Wrappers.<GuardianBinding>lambdaUpdate()
                .set(GuardianBinding::getStatus, status)
                .set(GuardianBinding::getConfirmedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(GuardianBinding::getId, binding.getId())
                .eq(GuardianBinding::getStatus, binding.getStatus());
        if (note != null && !note.isBlank()) {
            update.set(GuardianBinding::getDecisionNote, note);
        }
        return guardianBindingMapper.update(null, update);
    }

    private void promoteNextPrimary(String protecteeUid) {
        GuardianBinding next = guardianBindingMapper.selectList(Wrappers.<GuardianBinding>lambdaQuery()
                        .eq(GuardianBinding::getProtecteeUid, protecteeUid)
                        .eq(GuardianBinding::getStatus, "ACTIVE")
                        .orderByAsc(GuardianBinding::getConfirmedAt)
                        .orderByAsc(GuardianBinding::getId))
                .stream().findFirst().orElse(null);
        if (next != null) {
            next.setIsPrimary(true);
            guardianBindingMapper.updateById(next);
        }
    }

    private void requireActiveRole(String userId, String roleCode) {
        Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getStatus, "ACTIVE"));
        if (count == 0) {
            UserAccount account = userAccountMapper.selectById(userId);
            if (account != null && roleCode.equals(account.getRoleCode())
                    && "ENABLED".equals(account.getStatus())) {
                return;
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "当前用户不具备" + roleCode + "角色");
        }
    }

    private boolean hasRole(String userId, String roleCode) {
        return userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getStatus, "ACTIVE")) > 0
                || roleCode.equals(userAccountMapper.selectById(userId).getRoleCode());
    }

    private BindingResponse toBindingResponse(GuardianBinding binding) {
        return new BindingResponse(binding.getId(), binding.getStatus(), binding.getGuardianUid(),
                binding.getProtecteeUid(), Boolean.TRUE.equals(binding.getPermanentFlag()),
                Boolean.TRUE.equals(binding.getIsPrimary()), binding.getRequestedBy());
    }
}
