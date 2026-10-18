package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.detect.CocoLabels;

import org.junit.Test;

public class CocoLabelsTest {

    @Test
    public void defaultLabels_matchCocoWhenClassCountMatches() {
        String[] labels = CocoLabels.defaultLabels(80);
        assertEquals(80, labels.length);
        assertEquals("person", labels[0]);
        assertEquals("toothbrush", labels[79]);
    }

    @Test
    public void defaultLabels_fallBackToPlaceholdersOnCustomModel() {
        String[] labels = CocoLabels.defaultLabels(3);
        assertEquals(3, labels.length);
        assertEquals("class 0", labels[0]);
        assertEquals("class 2", labels[2]);
    }

    @Test
    public void isObstacle_acceptsTrafficHazards() {
        assertTrue(CocoLabels.isObstacle("person"));
        assertTrue(CocoLabels.isObstacle("car"));
        assertTrue(CocoLabels.isObstacle("traffic light"));
        assertTrue(CocoLabels.isObstacle("stop sign"));
        assertTrue(CocoLabels.isObstacle("dining table"));
        assertTrue(CocoLabels.isObstacle("potted plant"));
    }

    @Test
    public void isObstacle_rejectsNonHazards() {
        assertFalse(CocoLabels.isObstacle("banana"));
        assertFalse(CocoLabels.isObstacle("tv"));
        assertFalse(CocoLabels.isObstacle("book"));
    }

    /** script.py 的子串匹配会把 "carrot" 认成车辆，这里必须是整词匹配。 */
    @Test
    public void isObstacle_doesNotMatchSubstrings() {
        assertFalse(CocoLabels.isObstacle("carrot"));
        assertFalse(CocoLabels.isObstacle(null));
    }

    @Test
    public void translate_coversCocoClassesAndNormalizesSeparators() {
        assertEquals("行人", CocoLabels.translate("person"));
        assertEquals("汽车", CocoLabels.translate("car"));
        assertEquals("红绿灯", CocoLabels.translate("traffic light"));
        assertEquals("行人", CocoLabels.translate("PERSON"));
        assertEquals("红绿灯", CocoLabels.translate("traffic_light"));
    }

    @Test
    public void translate_keepsUnknownLabels() {
        assertEquals("wheelbarrow", CocoLabels.translate("wheelbarrow"));
    }
}
