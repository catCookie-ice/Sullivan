package com.sullivan.android.ui.guardian.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.data.api.dto.AlertDtos;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 「从会话找报警、从报警找位置」以及「把位置说成人话」的测试。
 *
 * <p>这一组对应一个具体需求：**监护人收到紧急求助时应当看到受护人的位置。**
 * 之前监护人端连报警接口都没有，只能看到一条不知来路的会话。
 */
public class AlertLocationSummaryTest {

    // ------------------------------------------------------------------
    // AlertQuery：会话 → 报警
    // ------------------------------------------------------------------

    private static AlertDtos.AlertDeliveryView delivery(String sessionId) {
        AlertDtos.AlertDeliveryView view = new AlertDtos.AlertDeliveryView();
        view.sessionId = sessionId;
        return view;
    }

    private static AlertDtos.AlertResponse alert(String id, AlertDtos.AlertLocation location,
                                                 AlertDtos.AlertDeliveryView... deliveries) {
        AlertDtos.AlertResponse response = new AlertDtos.AlertResponse();
        response.id = id;
        response.location = location;
        response.deliveries = Arrays.asList(deliveries);
        return response;
    }

    private static AlertDtos.AlertLocation location(double lat, double lon, Long ageSeconds) {
        AlertDtos.AlertLocation loc = new AlertDtos.AlertLocation();
        loc.latitude = lat;
        loc.longitude = lon;
        loc.ageSeconds = ageSeconds;
        return loc;
    }

    /** 能从多条报警、多条投递里找出会话对应的那一条。 */
    @Test
    public void findsAlertBySessionId() {
        List<AlertDtos.AlertResponse> alerts = Arrays.asList(
                alert("a1", location(1, 2, 5L), delivery("s-other")),
                alert("a2", location(3, 4, 5L), delivery("s-target")));
        AlertDtos.AlertResponse found = AlertQuery.alertForSession(alerts, "s-target");
        assertEquals("a2", found.id);
    }

    /**
     * 一条报警投递给多个监护人时，任何一个的会话 id 都要能命中。
     *
     * <p>这正是"多个监护人"那个需求带来的形状：同一条报警对应多条投递，
     * 每条投递有自己的 sessionId。只看第一条会漏掉第二个监护人。
     */
    @Test
    public void matchesAnyDeliveryNotJustFirst() {
        AlertDtos.AlertResponse shared = alert("a1", location(3, 4, 1L),
                delivery("s-guardian-1"), delivery("s-guardian-2"));
        assertEquals("a1", AlertQuery.alertForSession(
                Collections.singletonList(shared), "s-guardian-2").id);
    }

    @Test
    public void returnsNullWhenNoMatch() {
        assertNull(AlertQuery.alertForSession(
                Collections.singletonList(alert("a1", null, delivery("s1"))), "s2"));
    }

    @Test
    public void returnsNullForNullAndEmptyInputs() {
        assertNull(AlertQuery.alertForSession(null, "s1"));
        assertNull(AlertQuery.alertForSession(Collections.emptyList(), "s1"));
        assertNull(AlertQuery.alertForSession(
                Collections.singletonList(alert("a1", null, delivery("s1"))), null));
        assertNull(AlertQuery.alertForSession(
                Collections.singletonList(alert("a1", null, delivery("s1"))), ""));
    }

    /** 报警对象/投递列表为 null 不能崩（服务端字段可能缺席）。 */
    @Test
    public void toleratesNullsInsideList() {
        List<AlertDtos.AlertResponse> alerts = new ArrayList<>();
        alerts.add(null);
        AlertDtos.AlertResponse noDeliveries = new AlertDtos.AlertResponse();
        noDeliveries.id = "a2";
        alerts.add(noDeliveries);
        AlertDtos.AlertResponse nullDelivery = alert("a3", null, (AlertDtos.AlertDeliveryView) null);
        alerts.add(nullDelivery);
        assertNull(AlertQuery.alertForSession(alerts, "s1"));
    }

    @Test
    public void locationForSessionPropagatesNull() {
        assertNull(AlertQuery.locationForSession(
                Collections.singletonList(alert("a1", null, delivery("s1"))), "s1"));
    }

    @Test
    public void locationForSessionFindsLocation() {
        AlertDtos.AlertLocation loc = location(39.9, 116.4, 3L);
        AlertDtos.AlertLocation found = AlertQuery.locationForSession(
                Collections.singletonList(alert("a1", loc, delivery("s1"))), "s1");
        assertEquals(39.9, found.latitude, 1e-9);
    }

    // ------------------------------------------------------------------
    // AlertLocationSummary：坐标可用性
    // ------------------------------------------------------------------

    /** (0,0) 视为不可用 —— 实践中它是"字段没填"而不是真的有人在大西洋上。 */
    @Test
    public void zeroZeroIsNotUsable() {
        assertFalse(AlertLocationSummary.hasUsableCoordinates(location(0, 0, 1L)));
    }

    @Test
    public void normalCoordinatesAreUsable() {
        assertTrue(AlertLocationSummary.hasUsableCoordinates(location(39.935524, 116.493729, 1L)));
    }

    @Test
    public void outOfRangeCoordinatesAreNotUsable() {
        assertFalse(AlertLocationSummary.hasUsableCoordinates(location(91, 0, 1L)));
        assertFalse(AlertLocationSummary.hasUsableCoordinates(location(-91, 0, 1L)));
        assertFalse(AlertLocationSummary.hasUsableCoordinates(location(0, 181, 1L)));
        assertFalse(AlertLocationSummary.hasUsableCoordinates(location(0, -181, 1L)));
    }

    /** 经度恰好 0、纬度非 0 是合法的（本初子午线上）。 */
    @Test
    public void zeroLongitudeWithRealLatitudeIsUsable() {
        assertTrue(AlertLocationSummary.hasUsableCoordinates(location(51.477, 0, 1L)));
    }

    // ------------------------------------------------------------------
    // AlertLocationSummary：文案
    // ------------------------------------------------------------------

    @Test
    public void detailContainsCoordinates() {
        AlertDtos.AlertLocation loc = location(39.935524, 116.493729, 3L);
        String detail = AlertLocationSummary.detail(loc);
        assertTrue(detail, detail.contains("39.935524"));
        assertTrue(detail, detail.contains("116.493729"));
    }

    /** 新鲜度必须说出来：位置是缓存，旧的会把监护人指向错误地点。 */
    @Test
    public void detailMentionsFreshness() {
        assertTrue(AlertLocationSummary.detail(location(39.9, 116.4, 3L)).contains("刚刚"));
        assertTrue(AlertLocationSummary.detail(location(39.9, 116.4, 45L)).contains("45 秒前"));
        assertTrue(AlertLocationSummary.detail(location(39.9, 116.4, 300L)).contains("5 分钟前"));
    }

    @Test
    public void headlineMentionsAge() {
        assertEquals("受护人位置（刚刚）",
                AlertLocationSummary.headline(location(39.9, 116.4, 2L)));
    }

    /** 没有位置时 headline 与 detail 都返回 null，由调用方决定怎么提示。 */
    @Test
    public void nullLocationYieldsNullTexts() {
        assertNull(AlertLocationSummary.headline(null));
        assertNull(AlertLocationSummary.detail(null));
    }

    @Test
    public void unusableCoordinatesYieldNullDetail() {
        assertNull(AlertLocationSummary.detail(location(0, 0, 1L)));
    }

    /** ageSeconds 缺失时不能崩，也不能编造一个"0 秒前"。 */
    @Test
    public void missingAgeIsTolerated() {
        String detail = AlertLocationSummary.detail(location(39.9, 116.4, null));
        assertTrue(detail, detail.contains("39.9"));
        assertFalse(detail, detail.contains("前"));
    }

    /** 精度为空或非正数时不显示精度那一行。 */
    @Test
    public void accuracyIsOptional() {
        AlertDtos.AlertLocation loc = location(39.9, 116.4, 1L);
        loc.accuracyM = null;
        assertFalse(AlertLocationSummary.detail(loc).contains("精度"));
        loc.accuracyM = 0.0;
        assertFalse(AlertLocationSummary.detail(loc).contains("精度"));
        loc.accuracyM = 12.5;
        assertTrue(AlertLocationSummary.detail(loc).contains("13 米"));
    }
}
