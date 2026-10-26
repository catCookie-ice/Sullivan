package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.Alert;
import sullivan.serverjava.domain.AlertDelivery;
import sullivan.serverjava.domain.LatestLocation;
import sullivan.serverjava.domain.Notification;
import sullivan.serverjava.mapper.AlertDeliveryMapper;
import sullivan.serverjava.mapper.AlertMapper;
import sullivan.serverjava.mapper.NotificationMapper;
import sullivan.serverjava.web.AlertDtos;
import sullivan.serverjava.web.AlertDtos.AlertCreateRequest;
import sullivan.serverjava.web.AlertDtos.AlertResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报警创建与路由的口径测试。
 *
 * <p>重点覆盖三件容易搞错的事：
 * <ol>
 *   <li>有监护人时排期自动呼叫、没有监护人时**不**排期（否则会去呼一个不存在的对象）；</li>
 *   <li>用户关闭开关时状态是 SKIPPED 而不是 SCHEDULED；</li>
 *   <li>幂等键重复提交只产生一条报警。</li>
 * </ol>
 */
class AlertServiceTests {
    private static final String PROTECTEE = "protectee-1";
    private static final String GUARDIAN_A = "guardian-a";
    private static final String GUARDIAN_B = "guardian-b";

    private AlertMapper alertMapper;
    private AlertDeliveryMapper deliveryMapper;
    private NotificationMapper notificationMapper;
    private BindingService bindingService;
    private LocationService locationService;
    private AlertSettingService settingService;
    private RealtimeAlertDispatcher dispatcher;
    private AmapGeocodeService geocodeService;
    private AlertService service;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Alert.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AlertDelivery.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Notification.class);
    }

    @BeforeEach
    void setUp() {
        alertMapper = mock(AlertMapper.class);
        deliveryMapper = mock(AlertDeliveryMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        bindingService = mock(BindingService.class);
        locationService = mock(LocationService.class);
        settingService = mock(AlertSettingService.class);
        dispatcher = mock(RealtimeAlertDispatcher.class);
        // 逆地理编码 mock 默认返回 NONE（无 Key / 解析失败的常态路径）。
        geocodeService = mock(AmapGeocodeService.class);
        when(geocodeService.resolve(any(), any()))
                .thenReturn(AmapGeocodeService.GeoResolution.NONE);
        service = new AlertService(alertMapper, deliveryMapper, notificationMapper,
                bindingService, locationService, settingService, dispatcher, geocodeService);

        when(settingService.isAutoSessionEnabled(PROTECTEE)).thenReturn(true);
        when(settingService.effectiveDelaySeconds(PROTECTEE))
                .thenReturn(AlertSettingService.DEFAULT_DELAY_SECONDS);
    }

    private static AlertCreateRequest request(String key) {
        return new AlertCreateRequest("MANUAL", key, null, null);
    }

    @Test
    void alertWithGuardiansIsScheduledForAutoSession() {
        when(bindingService.activeGuardianIds(PROTECTEE))
                .thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));

        service.create(PROTECTEE, request("k1"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(AlertService.STATUS_DISPATCHED);
        assertThat(saved.getRouteType()).isEqualTo("ALL_GUARDIANS");
        assertThat(saved.getAutoSessionState()).isEqualTo(AlertService.AUTO_SCHEDULED);
        assertThat(saved.getAutoSessionDueAt()).isNotNull();
        // 延迟必须是 5 秒，不是别的数。
        assertThat(java.time.Duration.between(
                LocalDateTime.now(), saved.getAutoSessionDueAt()).getSeconds())
                .isBetween(3L, 6L);
    }

    /** 没有生效监护人：状态 NO_RECIPIENT，且**不排期**——没有可呼的人。 */
    @Test
    void alertWithoutGuardiansIsNotScheduled() {
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of());

        service.create(PROTECTEE, request("k2"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(AlertService.STATUS_NO_RECIPIENT);
        assertThat(saved.getRouteType()).isEqualTo("NONE");
        assertThat(saved.getAutoSessionState()).isEqualTo(AlertService.AUTO_NONE);
        assertThat(saved.getAutoSessionDueAt()).isNull();
        // 没有收件人就不该产生任何投递记录与通知。
        verify(deliveryMapper, never()).insert(any(AlertDelivery.class));
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /**
     * 报警携带位置时，服务端解析出的地名要随报警一起落库。
     * 解析失败（mock 返回 NONE）落 NULL，绝不能因为地名挂掉报警。
     */
    @Test
    void alertLocationAddressIsResolvedAndStored() {
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of(GUARDIAN_A));
        when(geocodeService.resolve(
                java.math.BigDecimal.valueOf(39.935524),
                java.math.BigDecimal.valueOf(116.493729)))
                .thenReturn(new AmapGeocodeService.GeoResolution("浙江省杭州市西湖区文三路", false));
        AlertDtos.AlertCaptureRequest location = new AlertDtos.AlertCaptureRequest(
                java.math.BigDecimal.valueOf(39.935524),
                java.math.BigDecimal.valueOf(116.493729), null, null);

        service.create(PROTECTEE, new AlertDtos.AlertCreateRequest(
                "MANUAL", "k3", null, location));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();
        assertThat(saved.getLocationAddress()).isEqualTo("浙江省杭州市西湖区文三路");
        // 解析成功不是超时：pending 必须为 false。
        assertThat(saved.getLocationAddressPending()).isFalse();
    }

    /**
     * 解析超时（PENDING）：报警**照常落库转发**，带 pending 标识、地址为空 ——
     * 「把求助送出去」优先于「把地名查出来」，接收方见到标识自行兜底解析。
     */
    @Test
    void geocodeTimeoutMarksPendingButStillDispatches() {
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of(GUARDIAN_A));
        when(geocodeService.resolve(any(), any()))
                .thenReturn(AmapGeocodeService.GeoResolution.PENDING);
        AlertDtos.AlertCaptureRequest location = new AlertDtos.AlertCaptureRequest(
                java.math.BigDecimal.valueOf(39.9),
                java.math.BigDecimal.valueOf(116.4), null, null);

        service.create(PROTECTEE, new AlertDtos.AlertCreateRequest(
                "MANUAL", "k4", null, location));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();
        // 报警没有被地名卡住：状态正常推进到 DISPATCHED（而不是被拖死或失败）。
        assertThat(saved.getStatus()).isEqualTo(AlertService.STATUS_DISPATCHED);
        assertThat(saved.getLatitude()).isEqualByComparingTo("39.9");
        assertThat(saved.getLocationAddress()).isNull();
        assertThat(saved.getLocationAddressPending()).isTrue();
    }

    /** 用户关掉开关：报警照常路由，但不排期自动呼叫。 */
    @Test
    void disabledSettingSkipsAutoSessionButStillDispatches() {
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of(GUARDIAN_A));
        when(settingService.isAutoSessionEnabled(PROTECTEE)).thenReturn(false);

        service.create(PROTECTEE, request("k3"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();

        // 关键点：报警本身仍然派发（监护人依然收到通知），只是不自动呼人。
        assertThat(saved.getStatus()).isEqualTo(AlertService.STATUS_DISPATCHED);
        assertThat(saved.getAutoSessionState()).isEqualTo(AlertService.AUTO_SKIPPED);
        assertThat(saved.getAutoSessionDueAt()).isNull();
        assertThat(saved.getRouteNote()).contains("用户已关闭自动呼叫");
        verify(deliveryMapper).insert(any(AlertDelivery.class));
    }

    @Test
    void deliveriesAreOrderedByGuardianOrder() {
        when(bindingService.activeGuardianIds(PROTECTEE))
                .thenReturn(List.of(GUARDIAN_A, GUARDIAN_B));

        service.create(PROTECTEE, request("k4"));

        ArgumentCaptor<AlertDelivery> captor = ArgumentCaptor.forClass(AlertDelivery.class);
        verify(deliveryMapper, times(2)).insert(captor.capture());
        List<AlertDelivery> saved = captor.getAllValues();

        // 「依次」的顺序必须落库：第一个监护人是 1，第二个是 2。
        assertThat(saved.get(0).getReceiverPrincipalId()).isEqualTo(GUARDIAN_A);
        assertThat(saved.get(0).getDispatchOrder()).isEqualTo(1);
        assertThat(saved.get(1).getReceiverPrincipalId()).isEqualTo(GUARDIAN_B);
        assertThat(saved.get(1).getDispatchOrder()).isEqualTo(2);
        assertThat(saved).allMatch(d -> "PENDING".equals(d.getStatus()));
    }

    /** 幂等：同一键重复提交返回已有报警，不新建、不重复通知监护人。 */
    @Test
    void duplicateIdempotencyKeyReturnsExistingAlert() {
        Alert existing = new Alert();
        existing.setId("al-existing");
        existing.setProtecteeUid(PROTECTEE);
        existing.setIdempotencyKey("k5");
        existing.setAlertType("MANUAL");
        existing.setStatus(AlertService.STATUS_DISPATCHED);
        existing.setAutoSessionState(AlertService.AUTO_SCHEDULED);
        when(alertMapper.selectOne(any())).thenReturn(existing);

        AlertResponse response = service.create(PROTECTEE, request("k5"));

        assertThat(response.id()).isEqualTo("al-existing");
        verify(alertMapper, never()).insert(any(Alert.class));
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /** 位置快照在创建时固化，之后 latest_location 被覆盖也不影响这条报警。 */
    @Test
    void locationSnapshotIsFrozenAtCreation() {
        LatestLocation latest = new LatestLocation();
        latest.setUserId(PROTECTEE);
        latest.setStatus("AVAILABLE");
        latest.setLatitude(new BigDecimal("39.935524"));
        latest.setLongitude(new BigDecimal("116.493729"));
        latest.setCapturedAt(LocalDateTime.now().minusSeconds(3));
        when(locationService.loadEntity(PROTECTEE)).thenReturn(latest);
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of(GUARDIAN_A));

        service.create(PROTECTEE, request("k6"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();

        assertThat(saved.getLatitude()).isEqualByComparingTo("39.935524");
        assertThat(saved.getLongitude()).isEqualByComparingTo("116.493729");
        assertThat(saved.getLocationCapturedAt()).isNotNull();
    }

    /** 从未上报位置：报警照常创建，位置留空，不编一个默认坐标。 */
    @Test
    void alertWithoutLocationStillCreated() {
        when(locationService.loadEntity(PROTECTEE)).thenReturn(null);
        when(bindingService.activeGuardianIds(PROTECTEE)).thenReturn(List.of(GUARDIAN_A));

        service.create(PROTECTEE, request("k7"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        assertThat(captor.getValue().getLatitude()).isNull();
        assertThat(captor.getValue().getLongitude()).isNull();
    }

    /** 非监护人读报警详情：一律 404，不泄露「这条报警存在」。 */
    @Test
    void outsiderGetsNotFoundNotForbidden() {
        Alert alert = new Alert();
        alert.setId("al1");
        alert.setProtecteeUid(PROTECTEE);
        alert.setStatus(AlertService.STATUS_DISPATCHED);
        when(alertMapper.selectById("al1")).thenReturn(alert);
        when(bindingService.hasActiveGuardianship("outsider", PROTECTEE)).thenReturn(false);

        assertThatThrownBy(() -> service.get("outsider", "al1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void activeGuardianCanReadAlert() {
        Alert alert = new Alert();
        alert.setId("al1");
        alert.setProtecteeUid(PROTECTEE);
        alert.setStatus(AlertService.STATUS_DISPATCHED);
        when(alertMapper.selectById("al1")).thenReturn(alert);
        when(bindingService.hasActiveGuardianship(GUARDIAN_A, PROTECTEE)).thenReturn(true);

        assertThat(service.get(GUARDIAN_A, "al1").id()).isEqualTo("al1");
    }

    // ------------------------------------------------------------------
    // 报警携带的实时位置优先于缓存
    // ------------------------------------------------------------------

    private static AlertCreateRequest withLocation(String key) {
        return new AlertCreateRequest("MANUAL", key, null,
                new AlertDtos.AlertCaptureRequest(
                        new BigDecimal("30.5"), new BigDecimal("104.1"),
                        new BigDecimal("15.0"),
                        LocalDateTime.of(2026, 10, 24, 17, 53, 0)));
    }

    /**
     * 请求里带了位置时**以请求为准**，不读缓存。
     *
     * <p>这是「监护人能看到受护人位置」能否成立的关键：缓存行实测可能是
     * 8 分钟前的，而报警是要拿去找人的 —— 旧坐标比没有坐标更糟。
     */
    @Test
    void requestLocationWinsOverCachedLocation() {
        LatestLocation cached = new LatestLocation();
        cached.setStatus("AVAILABLE");
        cached.setLatitude(new BigDecimal("1.0"));
        cached.setLongitude(new BigDecimal("2.0"));
        cached.setCapturedAt(LocalDateTime.of(2026, 10, 24, 17, 0, 0));
        when(locationService.loadEntity(PROTECTEE)).thenReturn(cached);

        service.create(PROTECTEE, withLocation("k1"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        Alert saved = captor.getValue();
        assertThat(saved.getLatitude()).isEqualByComparingTo("30.5");
        assertThat(saved.getLongitude()).isEqualByComparingTo("104.1");
        assertThat(saved.getLocationCapturedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 17, 53, 0));
        // 用了请求的位置就不该再读缓存。
        verify(locationService, never()).loadEntity(PROTECTEE);
    }

    /** 请求没带位置时回落到缓存（客户端确实拿不到定位的情况）。 */
    @Test
    void fallsBackToCachedLocationWhenRequestHasNone() {
        LatestLocation cached = new LatestLocation();
        cached.setStatus("AVAILABLE");
        cached.setLatitude(new BigDecimal("1.0"));
        cached.setLongitude(new BigDecimal("2.0"));
        cached.setCapturedAt(LocalDateTime.of(2026, 10, 24, 17, 0, 0));
        when(locationService.loadEntity(PROTECTEE)).thenReturn(cached);

        service.create(PROTECTEE, request("k1"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        assertThat(captor.getValue().getLatitude()).isEqualByComparingTo("1.0");
    }

    /** 请求带了位置时顺手刷新缓存。 */
    @Test
    void requestLocationIsWrittenBackToCache() {
        service.create(PROTECTEE, withLocation("k1"));

        verify(locationService).report(eq(PROTECTEE), any());
    }

    /** 写回缓存失败不能影响报警本身 —— 位置已经进 alert 了。 */
    @Test
    void cacheWriteFailureDoesNotBreakAlert() {
        when(locationService.report(eq(PROTECTEE), any()))
                .thenThrow(new RuntimeException("数据库暂时不可用"));

        assertThat(service.create(PROTECTEE, withLocation("k1")).id()).isNotBlank();
        verify(alertMapper).insert(any(Alert.class));
    }

    /** 坐标缺一个时不能用（否则会写进半个坐标）。 */
    @Test
    void nullLongitudeInRequestIsIgnored() {
        LatestLocation cached = new LatestLocation();
        cached.setStatus("AVAILABLE");
        cached.setLatitude(new BigDecimal("1.0"));
        cached.setLongitude(new BigDecimal("2.0"));
        when(locationService.loadEntity(PROTECTEE)).thenReturn(cached);

        AlertCreateRequest halfNull = new AlertCreateRequest("MANUAL", "k1", null,
                new AlertDtos.AlertCaptureRequest(
                        new BigDecimal("30.5"), null, null, null));

        service.create(PROTECTEE, halfNull);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        assertThat(captor.getValue().getLatitude()).isEqualByComparingTo("1.0");
    }

    /** capturedAt 缺失时用服务端当前时间，不能留 null（否则 ageSeconds 算出天量）。 */
    @Test
    void missingCapturedAtDefaultsToNow() {
        AlertCreateRequest noCapturedAt = new AlertCreateRequest("MANUAL", "k1", null,
                new AlertDtos.AlertCaptureRequest(
                        new BigDecimal("30.5"), new BigDecimal("104.1"), null, null));

        LocalDateTime before = LocalDateTime.now().minusSeconds(5);
        service.create(PROTECTEE, noCapturedAt);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertMapper).insert(captor.capture());
        assertThat(captor.getValue().getLocationCapturedAt()).isAfter(before);
    }

    // ------------------------------------------------------------------
    // myAlerts：监护人必须能看到「投递给我的」报警
    // ------------------------------------------------------------------

    private static Alert alert(String id, String protecteeUid, LocalDateTime createdAt) {
        Alert alert = new Alert();
        alert.setId(id);
        alert.setProtecteeUid(protecteeUid);
        alert.setStatus(AlertService.STATUS_DISPATCHED);
        alert.setCreatedAt(createdAt);
        return alert;
    }

    private static AlertDelivery deliveryTo(String alertId, String receiverUid) {
        AlertDelivery delivery = new AlertDelivery();
        delivery.setAlertId(alertId);
        delivery.setReceiverPrincipalId(receiverUid);
        return delivery;
    }

    /**
     * 监护人调 {@code GET /alerts} 必须能拿到**投递给他的**报警。
     *
     * <p>这是「监护人应当看到受护人位置」这条需求的服务端前提：
     * 原来只按 {@code protecteeUid = callerId} 查，监护人自己不是受护人，
     * 拿到的永远是空列表 —— 于是客户端根本没有途径知道位置在哪。
     */
    @Test
    void guardianSeesAlertsDeliveredToThem() {
        when(deliveryMapper.selectList(any()))
                .thenReturn(List.of(deliveryTo("al1", GUARDIAN_A)));
        // 两次 selectList：第一次查"我发起的"（监护人没有），第二次查"投递给我的"。
        when(alertMapper.selectList(any()))
                .thenReturn(List.of(), List.of(alert("al1", PROTECTEE, LocalDateTime.now())));

        List<AlertResponse> result = service.myAlerts(GUARDIAN_A);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("al1");
        // 位置必须一起返回 —— 客户端靠它告诉监护人"往哪儿去"。
        assertThat(result.get(0).protecteeUid()).isEqualTo(PROTECTEE);
    }

    /** 受护人看自己的报警不受影响。 */
    @Test
    void protecteeStillSeesOwnAlerts() {
        when(alertMapper.selectList(any()))
                .thenReturn(List.of(alert("al1", PROTECTEE, LocalDateTime.now())));
        when(deliveryMapper.selectList(any())).thenReturn(List.of());

        List<AlertResponse> result = service.myAlerts(PROTECTEE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("al1");
    }

    /** 同一分页里两类来源都要合并，而不是互相覆盖。 */
    @Test
    void mergesBothSourcesAndSortsByCreatedAtDesc() {
        LocalDateTime older = LocalDateTime.of(2026, 10, 24, 10, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 10, 24, 12, 0, 0);
        Alert own = alert("al-own", PROTECTEE, older);
        Alert delivered = alert("al-delivered", "someone-else", newer);

        when(deliveryMapper.selectList(any()))
                .thenReturn(List.of(deliveryTo("al-delivered", PROTECTEE)));
        when(alertMapper.selectList(any())).thenReturn(List.of(own), List.of(delivered));

        List<AlertResponse> result = service.myAlerts(PROTECTEE);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("al-delivered");
        assertThat(result.get(1).id()).isEqualTo("al-own");
    }

    /** 没有任何投递记录时不能去查报警表（避免生成 `IN ()` 这种非法 SQL）。 */
    @Test
    void noDeliveriesDoesNotQueryAlertTableAgain() {
        when(alertMapper.selectList(any())).thenReturn(List.of());
        when(deliveryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.myAlerts(GUARDIAN_A)).isEmpty();
        verify(alertMapper, times(1)).selectList(any());
    }
}
