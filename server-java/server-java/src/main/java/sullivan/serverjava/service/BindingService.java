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
