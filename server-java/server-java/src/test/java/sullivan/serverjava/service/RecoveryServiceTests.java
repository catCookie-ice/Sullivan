package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryServiceTests {
    private AccountRecoveryMapper recoveryMapper;
    private AccountRecoveryReminderMapper reminderMapper;
    private UserAccountMapper userAccountMapper;
    private OrganizationMapper organizationMapper;
    private RecoveryService service;

    @BeforeEach
    void setUp() {
        recoveryMapper = mock(AccountRecoveryMapper.class);
        reminderMapper = mock(AccountRecoveryReminderMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        service = new RecoveryService(recoveryMapper, reminderMapper, userAccountMapper, organizationMapper);
    }

    @Test
    void setContactsWritesPhoneAndEmailAsTwoRows() {
        service.setContacts("USER", "u_1", "13800138000", "zhangsan@example.com");

        ArgumentCaptor<AccountRecovery> captor = ArgumentCaptor.forClass(AccountRecovery.class);
        // 两条 insert：手机号 + 邮箱（强制转型，消除与批量 insert(Collection) 重载的歧义）
        verify(recoveryMapper, org.mockito.Mockito.times(2)).insert((AccountRecovery) captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        AccountRecovery phone = captor.getAllValues().stream()
                .filter(row -> "PHONE".equals(row.getRecoveryType())).findFirst().orElseThrow();
        assertThat(phone.getPrincipalType()).isEqualTo("USER");
        assertThat(phone.getPrincipalId()).isEqualTo("u_1");
        assertThat(phone.getPrefix()).isEqualTo("8000");
        assertThat(phone.getCipherHash()).hasSize(64);
        AccountRecovery email = captor.getAllValues().stream()
                .filter(row -> "EMAIL".equals(row.getRecoveryType())).findFirst().orElseThrow();
        assertThat(email.getPrefix()).isEqualTo("zh");
        assertThat(email.getCipherHash()).hasSize(64);
    }

    @Test
    void setContactsIgnoresBlankFields() {
        service.setContacts("USER", "u_1", "  ", "");
        verify(recoveryMapper, never()).insert((AccountRecovery) any());
    }

    @Test
    void reminderRequiredWithoutAnyRowPrompts() {
        when(recoveryMapper.selectCount(any())).thenReturn(0L);
        when(reminderMapper.selectOne(any())).thenReturn(null);
        assertThat(service.reminderRequired("USER", "u_1", LocalDateTime.of(2026, 8, 12, 10, 0))).isTrue();
    }

    @Test
    void reminderRequiredIsFalseWhenRecoverySet() {
        when(recoveryMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.reminderRequired("USER", "u_1", LocalDateTime.of(2026, 8, 12, 10, 0))).isFalse();
    }

    @Test
    void reminderRequiredIsFalseWithinSevenDaysAfterRemindLater() {
        when(recoveryMapper.selectCount(any())).thenReturn(0L);
        AccountRecoveryReminder reminder = new AccountRecoveryReminder();
        reminder.setRemindNever(false);
        reminder.setLastRemindedAt(LocalDateTime.of(2026, 8, 10, 9, 0));
        when(reminderMapper.selectOne(any())).thenReturn(reminder);
        // 距今 2 天，未满一周
        assertThat(service.reminderRequired("USER", "u_1", LocalDateTime.of(2026, 8, 12, 10, 0))).isFalse();
    }

    @Test
    void reminderRequiredIsTrueAfterSevenDays() {
        when(recoveryMapper.selectCount(any())).thenReturn(0L);
        AccountRecoveryReminder reminder = new AccountRecoveryReminder();
        reminder.setRemindNever(false);
        reminder.setLastRemindedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        when(reminderMapper.selectOne(any())).thenReturn(reminder);
        // 距今超过一周
        assertThat(service.reminderRequired("USER", "u_1", LocalDateTime.of(2026, 8, 12, 10, 0))).isTrue();
    }

    @Test
    void reminderRequiredIsFalseAfterDismiss() {
        when(recoveryMapper.selectCount(any())).thenReturn(0L);
        AccountRecoveryReminder reminder = new AccountRecoveryReminder();
        reminder.setRemindNever(true);
        reminder.setLastRemindedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(reminderMapper.selectOne(any())).thenReturn(reminder);
        assertThat(service.reminderRequired("USER", "u_1", LocalDateTime.of(2026, 8, 12, 10, 0))).isFalse();
    }

    @Test
    void remindLaterUpsertsTimestampAndClearsNeverFlag() {
        AccountRecoveryReminder existing = new AccountRecoveryReminder();
        existing.setId("r_1");
        existing.setRemindNever(true);
        when(reminderMapper.selectOne(any())).thenReturn(existing);

        service.remindLater("USER", "u_1");

        verify(reminderMapper).updateById(existing);
        assertThat(existing.getRemindNever()).isFalse();
        assertThat(existing.getLastRemindedAt()).isNotNull();
    }

    @Test
    void dismissInsertsNewRowWhenNoneExists() {
        when(reminderMapper.selectOne(any())).thenReturn(null);
        service.dismissReminder("USER", "u_1");

        ArgumentCaptor<AccountRecoveryReminder> captor = ArgumentCaptor.forClass(AccountRecoveryReminder.class);
        verify(reminderMapper).insert(captor.capture());
        assertThat(captor.getValue().getRemindNever()).isTrue();
        assertThat(captor.getValue().getPrincipalId()).isEqualTo("u_1");
    }

    @Test
    void startRecoveryReturnsMaskedMethodsByLoginName() {
        when(userAccountMapper.selectOne(any())).thenReturn(account("u_9"));
        AccountRecovery phoneRow = new AccountRecovery();
        phoneRow.setRecoveryType("PHONE");
        phoneRow.setPrefix("8000");
        when(recoveryMapper.selectList(any())).thenReturn(List.of(phoneRow));

        List<RecoveryMethod> methods = service.startRecovery("USER", "zhangsan");

        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).type()).isEqualTo("PHONE");
        assertThat(methods.get(0).maskedPrefix()).isEqualTo("****8000");
    }

    @Test
    void startRecoveryReturnsEmptyWhenAccountMissing() {
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        assertThat(service.startRecovery("USER", "nobody")).isEmpty();
    }

    @Test
    void verifyRecoverySucceedsForMatchingContact() {
        when(userAccountMapper.selectOne(any())).thenReturn(account("u_9"));
        AccountRecovery row = new AccountRecovery();
        row.setRecoveryType("PHONE");
        row.setPrefix("8000");
        row.setCipherHash("cipher");
        when(recoveryMapper.selectOne(any())).thenReturn(row);

        // 需要让 codec 比对通过：构造与存储一致的联系方式
        sullivan.serverjava.common.RecoveryContactCodec.Segments segments =
                sullivan.serverjava.common.RecoveryContactCodec.split("13800138000");
        row.setCipherHash(sullivan.serverjava.common.RecoveryContactCodec.cipher(segments.prefix(), segments.remainder()));

        assertThat(service.verifyRecovery("USER", "zhangsan", "PHONE", "13800138000")).isTrue();
        assertThat(service.verifyRecovery("USER", "zhangsan", "PHONE", "13900138000")).isFalse();
    }

    @Test
    void verifyRecoveryFailsWhenNoRowOrNoAccount() {
        when(userAccountMapper.selectOne(any())).thenReturn(null);
        assertThat(service.verifyRecovery("USER", "nobody", "PHONE", "13800138000")).isFalse();

        when(userAccountMapper.selectOne(any())).thenReturn(account("u_9"));
        when(recoveryMapper.selectOne(any())).thenReturn(null);
        assertThat(service.verifyRecovery("USER", "zhangsan", "EMAIL", "zhangsan@example.com")).isFalse();
    }

    @Test
    void organizationRecoveryUsesOrganizationAccount() {
        Organization org = new Organization();
        org.setId("o_1");
        when(organizationMapper.selectOne(any())).thenReturn(org);
        when(recoveryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.startRecovery("ORGANIZATION", "blindsociety")).isEmpty();
        verify(organizationMapper).selectOne(any());
    }

    private UserAccount account(String id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        return account;
    }
}
