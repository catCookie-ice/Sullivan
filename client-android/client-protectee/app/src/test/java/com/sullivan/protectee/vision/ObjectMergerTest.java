package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;

import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.track.ObjectMerger;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ObjectMergerTest {

    private static Detection detection(String label, float left, float top,
                                       float right, float bottom) {
        int classId = Arrays.asList(CocoLabels.COCO_80).indexOf(label);
        return new Detection(classId, label, 0.9f, left, top, right, bottom);
    }

    /** 同一个人被检出两个高度重叠的框：必须合成一个，只播报一次。 */
    @Test
    public void overlappingBoxesOfSamePersonBecomeOne() {
        Detection a = detection("person", 0.40f, 0.50f, 0.55f, 0.95f);
        Detection b = detection("person", 0.44f, 0.52f, 0.59f, 0.96f);

        List<ObjectMerger.Merged> merged = ObjectMerger.merge(Arrays.asList(a, b));

        assertEquals(1, merged.size());
        assertEquals(2, merged.get(0).memberCount);
        // 并集框要盖住两个原始框。
        assertEquals(0.40f, merged.get(0).box.left, 0.0001f);
        assertEquals(0.59f, merged.get(0).box.right, 0.0001f);
    }

    /** 骑车的人：person 与 bicycle 是两个类别，NMS 不会抑制，但说的是同一个目标。 */
    @Test
    public void cyclistIsMergedIntoOnePerson() {
        Detection person = detection("person", 0.40f, 0.40f, 0.60f, 0.95f);
        Detection bicycle = detection("bicycle", 0.38f, 0.55f, 0.62f, 0.90f);

        List<ObjectMerger.Merged> merged = ObjectMerger.merge(Arrays.asList(person, bicycle));

        assertEquals(1, merged.size());
        // 行人优先：骑车的人该播"行人"。
        assertEquals("行人", CocoLabels.translate(merged.get(0).label));
    }

    /** 大框套小框：IoU 很小但包含度很高，同样是一个目标。 */
    @Test
    public void containmentCountsAsSameTarget() {
        Detection car = detection("car", 0.20f, 0.40f, 0.80f, 0.90f);
        Detection person = detection("person", 0.45f, 0.55f, 0.55f, 0.80f);

        assertEquals(1, ObjectMerger.merge(Arrays.asList(car, person)).size());
    }

    /** 并排站着的两个行人：离得够开，不能被并成一个人（否则会漏报一个）。 */
    @Test
    public void twoPedestriansStandingApartStaySeparate() {
        Detection left = detection("person", 0.10f, 0.50f, 0.30f, 0.95f);
        Detection right = detection("person", 0.55f, 0.50f, 0.75f, 0.95f);

        assertEquals(2, ObjectMerger.merge(Arrays.asList(left, right)).size());
    }

    /** 画面两侧的车与行人本来就不是一回事，不能被合并。 */
    @Test
    public void unrelatedObjectsStaySeparate() {
        Detection car = detection("car", 0.02f, 0.45f, 0.20f, 0.70f);
        Detection person = detection("person", 0.70f, 0.50f, 0.85f, 0.95f);

        assertEquals(2, ObjectMerger.merge(Arrays.asList(car, person)).size());
    }

    @Test
    public void emptyAndSingleInputsPassThrough() {
        assertEquals(0, ObjectMerger.merge(Collections.<Detection>emptyList()).size());

        List<ObjectMerger.Merged> single = ObjectMerger.merge(
                Collections.singletonList(detection("car", 0.4f, 0.5f, 0.6f, 0.7f)));
        assertEquals(1, single.size());
        assertEquals(1, single.get(0).memberCount);
    }
}
