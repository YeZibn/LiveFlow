package com.liveflow.core.resilience;

import com.liveflow.core.auth.AuthFailure;

/** 在密码计算前预留登录或注册额度。 */
public interface AuthRequestLimiter {

    /**
     * @param username 校验通过且保留原始大小写的账号名
     * @param source 可信连接来源
     * @throws AuthFailure 任一窗口超额，或无法确认额度
     */
    void checkLoginLimit(String username, String source);

    /**
     * @param source 可信连接来源
     * @throws AuthFailure 注册窗口超额，或无法确认额度
     */
    void checkRegistrationLimit(String source);
}
