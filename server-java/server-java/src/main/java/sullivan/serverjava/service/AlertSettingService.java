package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.ProtecteeAlertSetting;
import sullivan.serverjava.mapper.ProtecteeAlertSettingMapper;
import sullivan.serverjava.web.AlertDtos.AlertSettingResponse;

/**
 * 受护人的报警偏好。
 *
 * <p><b>默认开启，且「关掉」必须是一条显式的行。</b>读不到配置时返回
 * {@code autoSessionEnabled = true}：这是产品的默认行为，新用户不必先找到开关才算
 * 「有守护」。反过来，只有用户真的把开关拨到关，库里才会出现一行 FALSE ——
 * 这样「用户明确选择关闭」与「我们从未配置过」在数据上就分得开，
 * 日后排查「为什么没呼人」时不会两种情形混在一起。
 *
 * <p><b>为什么存服务端而不是只存手机本地。</b>用户换设备、重装 App 之后，
 * 这个开关的行为必须一致。只存在本地的话，同一场报警在两台手机上会有两种结果。
 */
@Service
public class AlertSettingService {

    /** 报警到发起会话的默认延迟（秒）。产品口径：报警出现 5 秒后开始呼人。 */
    public static final int DEFAULT_DELAY_SECONDS = 5;

    private final ProtecteeAlertSettingMapper settingMapper;

    public AlertSettingService(ProtecteeAlertSettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    /** 读设置；从未配置过时返回默认值（开启、5 秒）。 */
    public AlertSettingResponse get(String protecteeUid) {
        ProtecteeAlertSetting setting = find(protecteeUid);
        if (setting == null) {
            return new AlertSettingResponse(true, DEFAULT_DELAY_SECONDS);
        }
        return new AlertSettingResponse(
                setting.getAutoSessionEnabled() == null || setting.getAutoSessionEnabled(),
                setting.getAutoSessionDelaySeconds() == null
                        ? DEFAULT_DELAY_SECONDS : setting.getAutoSessionDelaySeconds());
    }

    /**
     * 报警时真正该用的延迟秒数。
     *
     * <p>与 {@link #get} 分开是刻意的：{@code get} 是给人看的（永远返回一个完整对象），
     * 这里是给调度用的（只关心「现在要不要排期、排多久」）。
     */
    public int effectiveDelaySeconds(String protecteeUid) {
        ProtecteeAlertSetting setting = find(protecteeUid);
        if (setting == null || setting.getAutoSessionDelaySeconds() == null) {
            return DEFAULT_DELAY_SECONDS;
        }
        return setting.getAutoSessionDelaySeconds();
    }

    /** 报警时是否应该自动发起会话。 */
    public boolean isAutoSessionEnabled(String protecteeUid) {
        ProtecteeAlertSetting setting = find(protecteeUid);
        // 缺行 = 默认开启。这一行不能写成 `setting != null && ...`：
        // 那会让「从没配置过」的新用户恰好失去求助功能，正好是反的。
        return setting == null || setting.getAutoSessionEnabled() == null
                || setting.getAutoSessionEnabled();
    }

    @Transactional
    public AlertSettingResponse update(String protecteeUid, boolean enabled) {
        ProtecteeAlertSetting setting = find(protecteeUid);
        if (setting == null) {
            setting = new ProtecteeAlertSetting();
            setting.setId(IdGenerator.next());
            setting.setStatus("ACTIVE");
            setting.setProtecteeUid(protecteeUid);
            setting.setAutoSessionEnabled(enabled);
            setting.setAutoSessionDelaySeconds(DEFAULT_DELAY_SECONDS);
            settingMapper.insert(setting);
            return new AlertSettingResponse(enabled, DEFAULT_DELAY_SECONDS);
        }
        setting.setAutoSessionEnabled(enabled);
        if (setting.getAutoSessionDelaySeconds() == null) {
            setting.setAutoSessionDelaySeconds(DEFAULT_DELAY_SECONDS);
        }
        settingMapper.updateById(setting);
        return new AlertSettingResponse(enabled, setting.getAutoSessionDelaySeconds());
    }

    private ProtecteeAlertSetting find(String protecteeUid) {
        return settingMapper.selectOne(Wrappers.<ProtecteeAlertSetting>lambdaQuery()
                .eq(ProtecteeAlertSetting::getProtecteeUid, protecteeUid));
    }
}
