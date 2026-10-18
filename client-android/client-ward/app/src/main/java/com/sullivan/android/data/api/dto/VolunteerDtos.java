package com.sullivan.android.data.api.dto;

/**
 * 志愿者兼任申请 DTO，字段名与服务端 {@code web/VolunteerDtos.java} 逐字对应。
 *
 * <p>注意这一套只服务**监护人兼任志愿者**：想当志愿者的人必须先有一个 GUARDIAN
 * 账号（注册时 {@code role=GUARDIAN}），再走这个申请把 VOLUNTEER 角色加成第二个角色。
 * 注册时任选 GUARDIAN 的人以后就再也申请不了志愿者 —— 这也是注册页必须把
 * 「监护人可兼任志愿者，志愿者不能反向兼任」写清楚的原因。
 */
public final class VolunteerDtos {

    private VolunteerDtos() {
    }

    /** {@code POST /api/v1/users/me/roles/volunteer} 请求体。body 可为 null。 */
    public static final class ApplyRequest {
        /** 可选，最长 1000 字。 */
        public String reason;

        public ApplyRequest(String reason) {
            this.reason = reason;
        }
    }

    /**
     * 申请记录。{@code POST}、{@code GET} 与 {@code cancel/{id}} 三个接口共用。
     *
     * <p>状态取值：PENDING（待审核）/ APPROVED（已通过）/ REJECTED（已拒绝）/
     * CANCELLED（已撤销）。**只有 PENDING 可以撤销** ——
     * 服务端没写这一条的软校验，但撤销一条已通过的申请没有任何语义，
     * 界面据此决定要不要显示撤销按钮。
     */
    public static final class ApplicationResponse {
        public String id;
        public String status;
        public String applicantUid;
        public String reason;
        /** 服务端是 LocalDateTime，这边按 String 收，只做显示截断。 */
        public String createdAt;
    }
}
