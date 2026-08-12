package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.common.RecoveryContactCodec;
import sullivan.serverjava.common.TimeUtil;
import sullivan.serverjava.domain.AccountRecovery;
import sullivan.serverjava.domain.AccountRecoveryReminder;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.AccountRecoveryMapper;
import sullivan.serverjava.mapper.AccountRecoveryReminderMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.web.RecoveryDtos.RecoveryMethod;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 找回联系方式（半加密存储）与找回密码验证、以及未设置联系方式的登录提醒逻辑。
 */
@Service
public class RecoveryService {
    /** "下次再说"后再次提醒的间隔天数。 */
    public static final long REMIND_DAYS = 7;

    private final AccountRecoveryMapper recoveryMapper;
    private final AccountRecoveryReminderMapper reminderMapper;
    private final UserAccountMapper userAccountMapper;
    private final OrganizationMapper organizationMapper;

    public RecoveryService(AccountRecoveryMapper recoveryMapper, AccountRecoveryReminderMapper reminderMapper,
                           UserAccountMapper userAccountMapper, OrganizationMapper organizationMapper) {
        this.recoveryMapper = recoveryMapper;
        this.reminderMapper = reminderMapper;
        this.userAccountMapper = userAccountMapper;
        this.organizationMapper = organizationMapper;
    }

    /** 注册时写入找回联系方式（手机号/邮箱均可选，非空者落库）。 */
    @Transactional
    public void setContacts(String principalType, String principalId, String phone, String email) {
        if (phone != null && !phone.isBlank()) {
            upsertContact(principalType, principalId, phone);
        }
        if (email != null && !email.isBlank()) {
            upsertContact(principalType, principalId, email);
        }
    }

    /** 设置或更新某主体的一种找回联系方式。 */
    @Transactional
    public void upsertContact(String principalType, String principalId, String contact) {
        RecoveryContactCodec.Segments segments = RecoveryContactCodec.split(contact);
        AccountRecovery existing = recoveryMapper.selectOne(Wrappers.<AccountRecovery>lambdaQuery()
                .eq(AccountRecovery::getPrincipalType, principalType)
                .eq(AccountRecovery::getPrincipalId, principalId)
                .eq(AccountRecovery::getRecoveryType, segments.type()));
        if (existing == null) {
            AccountRecovery row = new AccountRecovery();
            row.setId(IdGenerator.next());
            row.setStatus("ACTIVE");
            row.setPrincipalType(principalType);
            row.setPrincipalId(principalId);
            row.setRecoveryType(segments.type());
            row.setPrefix(segments.prefix());
            row.setCipherHash(RecoveryContactCodec.cipher(segments.prefix(), segments.remainder()));
            recoveryMapper.insert(row);
        } else {
            existing.setPrefix(segments.prefix());
            existing.setCipherHash(RecoveryContactCodec.cipher(segments.prefix(), segments.remainder()));
            recoveryMapper.updateById(existing);
        }
    }

    /** 该主体是否已设置至少一种找回联系方式。 */
    public boolean hasRecovery(String principalType, String principalId) {
        return countRecoveries(principalType, principalId) > 0;
    }

    /** 登录后是否需要提醒设置找回联系方式（默认当前时间）。 */
    public boolean reminderRequired(String principalType, String principalId) {
        return reminderRequired(principalType, principalId, TimeUtil.now());
    }

    /**
     * 提醒判定：已设置联系方式 → 不提醒；"不再提醒" → 不提醒；
     * 距上次"下次再说"未满一周 → 不提醒；其余（含首次登录无记录）→ 提醒。
     *
     * @param now 传入当前时间便于测试
     */
    public boolean reminderRequired(String principalType, String principalId, LocalDateTime now) {
        if (hasRecovery(principalType, principalId)) {
            return false;
        }
        AccountRecoveryReminder reminder = findReminder(principalType, principalId);
        if (reminder == null) {
            return true;
        }
        if (Boolean.TRUE.equals(reminder.getRemindNever())) {
            return false;
        }
        LocalDateTime last = reminder.getLastRemindedAt();
        if (last == null) {
            return true;
        }
        return !last.plusDays(REMIND_DAYS).isAfter(now);
    }

    /** "下次再说"：记录本次提醒时间，一周后才再次提醒。 */
    @Transactional
    public void remindLater(String principalType, String principalId) {
        AccountRecoveryReminder reminder = findReminder(principalType, principalId);
        if (reminder == null) {
            reminder = newReminder(principalType, principalId);
            reminder.setRemindNever(false);
            reminder.setLastRemindedAt(TimeUtil.now());
            reminderMapper.insert(reminder);
        } else {
            reminder.setRemindNever(false);
            reminder.setLastRemindedAt(TimeUtil.now());
            reminderMapper.updateById(reminder);
        }
    }

    /** "不再提醒"：永久关闭提醒。 */
    @Transactional
    public void dismissReminder(String principalType, String principalId) {
        AccountRecoveryReminder reminder = findReminder(principalType, principalId);
        if (reminder == null) {
            reminder = newReminder(principalType, principalId);
            reminder.setRemindNever(true);
            reminderMapper.insert(reminder);
        } else {
            reminder.setRemindNever(true);
            reminderMapper.updateById(reminder);
        }
    }

    /** 某主体已设置的全部找回方式（含掩码前缀）。 */
    public List<RecoveryMethod> methods(String principalType, String principalId) {
        return recoveryMapper.selectList(Wrappers.<AccountRecovery>lambdaQuery()
                        .eq(AccountRecovery::getPrincipalType, principalType)
                        .eq(AccountRecovery::getPrincipalId, principalId)).stream()
                .map(row -> new RecoveryMethod(row.getRecoveryType(),
                        RecoveryContactCodec.masked(row.getRecoveryType(), row.getPrefix())))
                .toList();
    }

    /** 找回流程第一步：按登录名返回该主体可用的找回方式（含掩码前缀）。 */
    public List<RecoveryMethod> startRecovery(String principalType, String loginName) {
        String principalId = findPrincipalId(principalType, loginName);
        return principalId == null ? List.of() : methods(principalType, principalId);
    }

    /** 找回流程验证：补全的联系方式与该主体对应方式的存储两段是否一致。 */
    public boolean verifyRecovery(String principalType, String loginName, String recoveryType, String fullContact) {
        String principalId = findPrincipalId(principalType, loginName);
        if (principalId == null) {
            return false;
        }
        AccountRecovery row = recoveryMapper.selectOne(Wrappers.<AccountRecovery>lambdaQuery()
                .eq(AccountRecovery::getPrincipalType, principalType)
                .eq(AccountRecovery::getPrincipalId, principalId)
                .eq(AccountRecovery::getRecoveryType, recoveryType));
        if (row == null) {
            return false;
        }
        return RecoveryContactCodec.matches(fullContact, row.getPrefix(), row.getCipherHash());
    }

    private long countRecoveries(String principalType, String principalId) {
        return recoveryMapper.selectCount(Wrappers.<AccountRecovery>lambdaQuery()
                .eq(AccountRecovery::getPrincipalType, principalType)
                .eq(AccountRecovery::getPrincipalId, principalId));
    }

    private AccountRecoveryReminder findReminder(String principalType, String principalId) {
        return reminderMapper.selectOne(Wrappers.<AccountRecoveryReminder>lambdaQuery()
                .eq(AccountRecoveryReminder::getPrincipalType, principalType)
                .eq(AccountRecoveryReminder::getPrincipalId, principalId));
    }

    private AccountRecoveryReminder newReminder(String principalType, String principalId) {
        AccountRecoveryReminder reminder = new AccountRecoveryReminder();
        reminder.setId(IdGenerator.next());
        reminder.setStatus("ACTIVE");
        reminder.setPrincipalType(principalType);
        reminder.setPrincipalId(principalId);
        return reminder;
    }

    private String findPrincipalId(String principalType, String loginName) {
        return switch (principalType) {
            case "USER" -> {
                UserAccount account = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                        .eq(UserAccount::getLoginName, loginName));
                yield account == null ? null : account.getId();
            }
            case "ORGANIZATION" -> {
                Organization organization = organizationMapper.selectOne(Wrappers.<Organization>lambdaQuery()
                        .eq(Organization::getLoginName, loginName));
                yield organization == null ? null : organization.getId();
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的主体类型");
        };
    }
}
