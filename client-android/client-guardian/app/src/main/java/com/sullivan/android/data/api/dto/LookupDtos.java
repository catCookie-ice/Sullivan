package com.sullivan.android.data.api.dto;

import java.util.List;

/**
 * 账号查找 DTO，字段名与服务端 {@code web/LookupDtos.java} 逐字对应。
 *
 * <p>用途只有一个：发起绑定时用户手里只有「对方的登录名」，而
 * {@code POST /bindings} 要的是 {@code targetId} —— 得先用登录名换到账号 ID。
 * 服务端没有「按登录名搜索」的接口，所以这条路径要求输入**完整且精确**的登录名。
 */
public final class LookupDtos {

    private LookupDtos() {
    }

    /** {@code GET /api/v1/users/{userId}} 响应体。 */
    public static final class UserResponse {
        public String id;
        /** ENABLED 或 DISABLED。 */
        public String status;
        public String loginName;
        public String displayName;
        /** 主角色：PROTECTEE / GUARDIAN / VOLUNTEER。 */
        public String roleCode;
        /** 当前生效的角色集合；监护人可兼任志愿者，此时是 ["GUARDIAN","VOLUNTEER"]。 */
        public List<String> roles;
        /** 是否兼任志愿者（同时具备 GUARDIAN 与 VOLUNTEER）。 */
        public boolean alsoVolunteer;
    }
}
