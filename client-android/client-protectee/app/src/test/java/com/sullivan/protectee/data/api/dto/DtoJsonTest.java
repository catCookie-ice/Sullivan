package com.sullivan.protectee.data.api.dto;

import static org.junit.Assert.assertEquals;
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
