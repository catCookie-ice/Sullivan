package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.LatestLocation;
import sullivan.serverjava.mapper.LatestLocationMapper;
import sullivan.serverjava.web.AlertDtos.AlertLocation;
import sullivan.serverjava.web.AlertDtos.LocationReportRequest;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 最新位置的上报与读取。
 *
 * <p><b>只存最新一条。</b>表上 {@code user_id} 是唯一键，上报是「覆盖」而不是「追加」。
 * 本版只需要回答「报警那一刻他在哪」，存轨迹会引入保留期与删除义务，超出 [MINI]。
 *
 * <p><b>新鲜度由 {@code captured_at} 算，不用 {@code received_at}。</b>设备可能离线
 * 补报：一条 20 分钟前采集的位置，其 {@code received_at} 是刚刚，但它的信息价值
 * 已经很低了。把这两个时刻分开存，就是为了让「多久之前的位置」这个问题有正确答案。
 */
@Service
public class LocationService {

    /**
     * 位置超过这个时长就不再有参考价值。
     *
     * <p>10 分钟≈一个人正常步速能走出约 800 米。超过这个量级，「他现在在哪」与
     * 「这个坐标」已经不是同一个问题了，此时宁可说不确定，也不该给出一个会误导
     * 救援方向的坐标。
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final LatestLocationMapper latestLocationMapper;

    public LocationService(LatestLocationMapper latestLocationMapper) {
        this.latestLocationMapper = latestLocationMapper;
    }

    /**
     * 上报（覆盖）最新位置。
     *
     * <p>用「先查再插/更」而不是 UPSERT：唯一键冲突时的覆盖逻辑在 MySQL 上要写
     * {@code ON DUPLICATE KEY UPDATE}，而那段 SQL 里的 {@code version} 自增和
     * MyBatis-Plus 的乐观锁语义会打架。表上只有一行一个用户，两次查询的成本可以忽略。
     */
    @Transactional
    public AlertLocation report(String userId, LocationReportRequest request) {
        LocalDateTime capturedAt = request.capturedAt() == null
                ? LocalDateTime.now() : request.capturedAt();
        // 客户端时钟可能比服务端快，未来的时间会让「距今多少秒」变成负数。
        // 夹到现在，宁可认为它刚刚采集。
        LocalDateTime now = LocalDateTime.now();
        if (capturedAt.isAfter(now)) {
            capturedAt = now;
        }

        LatestLocation existing = latestLocationMapper.selectOne(
                Wrappers.<LatestLocation>lambdaQuery().eq(LatestLocation::getUserId, userId));

        if (existing == null) {
            LatestLocation created = new LatestLocation();
            created.setId(IdGenerator.next());
            created.setStatus("AVAILABLE");
            created.setUserId(userId);
            created.setLatitude(request.latitude());
            created.setLongitude(request.longitude());
            created.setAccuracyM(request.accuracyM());
            created.setCapturedAt(capturedAt);
            created.setReceivedAt(now);
            latestLocationMapper.insert(created);
            return toView(created);
        }

        existing.setStatus("AVAILABLE");
        existing.setLatitude(request.latitude());
        existing.setLongitude(request.longitude());
        existing.setAccuracyM(request.accuracyM());
        existing.setCapturedAt(capturedAt);
        existing.setReceivedAt(now);
        latestLocationMapper.updateById(existing);
        return toView(existing);
    }

    /**
     * 读某个用户的最新位置快照。
     *
     * @return 从未上报过（或已撤销）时返回 {@code null} —— 调用方据此在报警里留空，
     *         而不是编一个坐标
     */
    public AlertLocation latestOf(String userId) {
        LatestLocation latest = latestLocationMapper.selectOne(
                Wrappers.<LatestLocation>lambdaQuery().eq(LatestLocation::getUserId, userId));
        if (latest == null || !"AVAILABLE".equals(latest.getStatus())) {
            return null;
        }
        return toView(latest);
    }

    /**
     * 该位置是否已经过时。
     *
     * <p>过时的位置仍然会随报警一起发出去（聊胜于无，至少是最后已知位置），
     * 但会带上 {@code ageSeconds}，让监护人自己判断可信度 ——
     * 不替用户隐藏「这条信息可能不准」这件事。
     */
    public static boolean isStale(AlertLocation location) {
        return location != null && location.ageSeconds() != null
                && location.ageSeconds() > STALE_AFTER.getSeconds();
    }

    private AlertLocation toView(LatestLocation location) {
        long ageSeconds = Math.max(0L,
                Duration.between(location.getCapturedAt(), LocalDateTime.now()).getSeconds());
        return new AlertLocation(location.getLatitude(), location.getLongitude(),
                location.getAccuracyM(), location.getCapturedAt(), ageSeconds);
    }

    /** 供报警服务在同一个事务里直接读实体（避免二次查询）。 */
    LatestLocation loadEntity(String userId) {
        return latestLocationMapper.selectOne(
                Wrappers.<LatestLocation>lambdaQuery().eq(LatestLocation::getUserId, userId));
    }
}
