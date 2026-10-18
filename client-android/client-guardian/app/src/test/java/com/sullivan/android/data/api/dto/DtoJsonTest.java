package com.sullivan.android.data.api.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

/**
 * DTO 字段名的锁。
 *
 * <p>Gson 按字段名反射匹配，字段名写错**不会编译失败**，只会在运行时悄悄留下 null。
 * release 里开了 R8，这个风险更高一层（虽然 {@code proguard-rules.pro} 已经 keep 住
 * {@code data.api.dto.**}，但那条规则本身也可能被误删）。
 *
 * <p>所以这里不构造对象去断言 getter，而是**用服务端真实响应的原样 JSON 反序列化** ——
 * 断言的是「这份 JSON 能不能落进 DTO」，也就是线上真正发生的事。
 * 样本取自服务端的 {@code @Schema(example=...)} 与开发日记里记下的真实响应。
 */
public class DtoJsonTest {

    private static final Gson GSON = new Gson();

    @Test
    public void bindingResponseMatchesServerJson() {
        String json = "{\"id\":\"b_1234567890\",\"status\":\"ACTIVE\",\"guardianId\":\"u_111\","
                + "\"protecteeId\":\"u_987\",\"permanent\":true,\"primary\":true,"
                + "\"requestedBy\":\"u_111\"}";

        BindingDtos.BindingResponse binding = GSON.fromJson(json, BindingDtos.BindingResponse.class);

        assertNotNull(binding);
        assertEquals("b_1234567890", binding.id);
        assertEquals("ACTIVE", binding.status);
        assertEquals("u_111", binding.guardianId);
        assertEquals("u_987", binding.protecteeId);
        assertTrue(binding.permanent);
        assertTrue(binding.primary);
        assertEquals("u_111", binding.requestedBy);
    }

    @Test
    public void activityPageResultMatchesServerIPageJson() {
        // MyBatis-Plus 的 IPage 还会多序列化出 orders / optimizeCountSql 等字段，
        // Gson 对多余字段是宽容的 —— 这条断言就是锁住「多出来也不炸」。
        String json = "{\"records\":[{\"id\":\"act_1\",\"status\":\"RECRUITING\","
                + "\"organizationId\":\"o_1\",\"organizationPublicName\":\"朝阳盲协\","
                + "\"title\":\"徒步陪伴\",\"description\":\"2 公里\",\"locationText\":\"朝阳公园南门\","
                + "\"locationLng\":116.493729,\"locationLat\":39.935524,"
                + "\"startsAt\":\"2026-09-01T09:00:00\",\"endsAt\":\"2026-09-01T12:00:00\","
                + "\"registrationStartsAt\":\"2026-08-20T00:00:00\","
                + "\"registrationEndsAt\":\"2026-08-30T23:59:59\","
                + "\"maxVolunteers\":20,\"approvedCount\":0,"
                + "\"createdAt\":\"2026-08-17T10:00:00\"}],"
                + "\"total\":1,\"size\":20,\"current\":1,\"pages\":1,"
                + "\"orders\":[],\"optimizeCountSql\":true}";

        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                ActivityDtos.PageResult<ActivityDtos.ActivityResponse>>() { }.getType();
        ActivityDtos.PageResult<ActivityDtos.ActivityResponse> page = GSON.fromJson(json, type);

        assertNotNull(page);
        assertEquals(1, page.total);
        assertEquals(1, page.records.size());

        ActivityDtos.ActivityResponse activity = page.records.get(0);
        assertEquals("徒步陪伴", activity.title);
        assertEquals("朝阳盲协", activity.organizationPublicName);
        assertEquals(Integer.valueOf(20), activity.maxVolunteers);
        assertEquals(Integer.valueOf(0), activity.approvedCount);
        // 时间一律 String：Gson 没有 LocalDateTime 适配器，用 Object 会得到一个 LinkedTreeMap。
        assertEquals("2026-09-01T09:00:00", activity.startsAt);
    }

    /**
     * 中继信封的 payload 必须是 {@code JsonElement}。
     *
     * <p>用 String 收的话，服务端透传的对象会被 Gson 读成一个字符串再发回去变成带引号的
     * JSON，对端就解析不出 {@code text} 了 —— 这种故障表现为「消息发出去了但对方看到乱码」。
     */
    @Test
    public void realtimeEnvelopeKeepsPayloadAsStructuredJson() {
        String json = "{\"type\":\"relay\",\"sessionId\":\"rs_1\",\"fromUid\":\"u_a\","
                + "\"toUid\":\"u_b\",\"messageId\":\"m_1\",\"ts\":1700000000000,"
                + "\"payload\":{\"text\":\"到家了吗\"}}";

        RealtimeDtos.Envelope envelope = GSON.fromJson(json, RealtimeDtos.Envelope.class);

        assertNotNull(envelope);
        assertEquals("relay", envelope.type);
        assertEquals("rs_1", envelope.sessionId);
        assertEquals(1700000000000L, envelope.ts);
        assertTrue(envelope.payload.isJsonObject());
        assertEquals("到家了吗", envelope.payload.getAsJsonObject().get("text").getAsString());
    }

    /** error 帧的 payload 是一个 JSON 字符串，不是对象；取文本的兜底逻辑要能吃下它。 */
    @Test
    public void realtimeErrorEnvelopePayloadIsAString() {
        String json = "{\"type\":\"error\",\"sessionId\":\"rs_1\",\"fromUid\":\"u_a\","
                + "\"toUid\":\"u_a\",\"payload\":\"双方不再存在生效绑定，消息未转发\"}";

        RealtimeDtos.Envelope envelope = GSON.fromJson(json, RealtimeDtos.Envelope.class);

        assertNotNull(envelope);
        assertTrue(envelope.payload.isJsonPrimitive());
        assertEquals("双方不再存在生效绑定，消息未转发", envelope.payload.getAsString());
    }

    /** 发出的 relay 帧要能被序列化成服务端认得的形状：四个业务字段 + payload 对象。 */
    @Test
    public void outgoingRelayEnvelopeSerializesWithExpectedShape() {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", "我到了");
        RealtimeDtos.Envelope envelope =
                RealtimeDtos.Envelope.relay("rs_1", "u_a", "u_b", payload);

        String json = GSON.toJson(envelope);
        JsonObject parsed = GSON.fromJson(json, JsonObject.class);

        assertEquals("relay", parsed.get("type").getAsString());
        assertEquals("u_a", parsed.get("fromUid").getAsString());
        assertEquals("u_b", parsed.get("toUid").getAsString());
        // payload 必须是嵌套对象，不能被序列化成字符串。
        assertTrue(parsed.get("payload").isJsonObject());
        assertEquals("我到了", parsed.getAsJsonObject("payload").get("text").getAsString());
    }

    @Test
    public void recoveryStartAllowsEmptyMethods() {
        // 账号不存在、或存在但没设过找回方式，两者都返回空列表 —— 客户端不能假设非空。
        RecoveryDtos.StartResponse empty = GSON.fromJson("{\"methods\":[]}",
                RecoveryDtos.StartResponse.class);
        assertNotNull(empty);
        assertNotNull(empty.methods);
        assertTrue(empty.methods.isEmpty());

        RecoveryDtos.StartResponse withPhone = GSON.fromJson(
                "{\"methods\":[{\"type\":\"PHONE\",\"maskedPrefix\":\"****8000\"}]}",
                RecoveryDtos.StartResponse.class);
        assertEquals(1, withPhone.methods.size());
        assertEquals("PHONE", withPhone.methods.get(0).type);
        assertEquals("****8000", withPhone.methods.get(0).maskedPrefix);
    }

    /** verify 不通过是 200 + verified=false，字段名别拼错，否则永远看起来「通过了」。 */
    @Test
    public void verifyResponseUsesVerifiedField() {
        RecoveryDtos.VerifyResponse failed = GSON.fromJson("{\"verified\":false}",
                RecoveryDtos.VerifyResponse.class);
        assertNotNull(failed);
        assertFalse(failed.verified);

        RecoveryDtos.VerifyResponse passed = GSON.fromJson("{\"verified\":true}",
                RecoveryDtos.VerifyResponse.class);
        assertTrue(passed.verified);
    }

    @Test
    public void userLookupResponseMatchesServerJson() {
        String json = "{\"id\":\"u_1\",\"status\":\"ENABLED\",\"loginName\":\"zhangsan\","
                + "\"displayName\":\"张三\",\"roleCode\":\"GUARDIAN\","
                + "\"roles\":[\"GUARDIAN\",\"VOLUNTEER\"],\"alsoVolunteer\":true}";

        LookupDtos.UserResponse user = GSON.fromJson(json, LookupDtos.UserResponse.class);

        assertNotNull(user);
        assertEquals("zhangsan", user.loginName);
        assertEquals("GUARDIAN", user.roleCode);
        assertEquals(2, user.roles.size());
        assertTrue(user.alsoVolunteer);
    }

    @Test
    public void volunteerApplicationMatchesServerJson() {
        String json = "{\"id\":\"va_1\",\"status\":\"PENDING\",\"applicantUid\":\"u_1\","
                + "\"reason\":\"希望周末参与社区志愿活动\",\"createdAt\":\"2026-08-17T10:00:00\"}";

        VolunteerDtos.ApplicationResponse application =
                GSON.fromJson(json, VolunteerDtos.ApplicationResponse.class);

        assertNotNull(application);
        assertEquals("va_1", application.id);
        assertEquals("PENDING", application.status);
        assertEquals("2026-08-17T10:00:00", application.createdAt);
    }

    @Test
    public void registerRequestSerializesRoleAndOptionalRecovery() {
        AuthDtos.RegisterUserRequest request = new AuthDtos.RegisterUserRequest(
                "zhangsan", "abc12345", "张三", "GUARDIAN", "13800138000", null);

        JsonObject json = GSON.toJsonTree(request).getAsJsonObject();

        assertEquals("zhangsan", json.get("loginName").getAsString());
        assertEquals("GUARDIAN", json.get("role").getAsString());
        assertEquals("13800138000", json.get("recoveryPhone").getAsString());
        // 没填的字段被 Gson 整个略去（默认不序列化 null），到了服务端就是 null。
        // 这是想要的：服务端的 @Size/@Pattern 对 null 一律放行，
        // 而空串会走 @Pattern("^$|...") 的正则分支 —— 两种写法在服务端不等价，
        // 所以这里**不要**为了「字段好看」把 null 改成空串。
        assertFalse(json.has("recoveryEmail"));
    }

    @Test
    public void sessionResponseMatchesServerJson() {
        String json = "{\"id\":\"rs_1\",\"status\":\"ACTIVE\",\"initiatorUid\":\"u_a\","
                + "\"targetUid\":\"u_b\",\"sessionType\":\"BASIC\","
                + "\"createdAt\":\"2026-08-17T10:00:00\"}";

        RealtimeDtos.SessionResponse session = GSON.fromJson(json, RealtimeDtos.SessionResponse.class);

        assertNotNull(session);
        assertEquals("ACTIVE", session.status);
        assertEquals("u_a", session.initiatorUid);
        assertEquals("u_b", session.targetUid);
        assertEquals("BASIC", session.sessionType);
    }
}
