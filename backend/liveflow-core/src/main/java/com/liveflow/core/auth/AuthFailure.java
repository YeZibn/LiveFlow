package com.liveflow.core.auth;

import org.springframework.security.core.AuthenticationException;

/**
 * 表示认证失败，保存稳定的认证错误码及公开提示。
 * 继承 Spring Security 的认证异常，以便会话校验失败时进入认证失败处理流程。
 */
public final class AuthFailure extends AuthenticationException {

    public enum Code {
        INVALID_REQUEST("请检查请求字段"),
        AUTH_INVALID_CREDENTIALS("账号或密码错误"),
        AUTH_REQUIRED("请先登录"),
        AUTH_ACCESS_INVALID("登录凭证无效"),
        AUTH_ACCESS_EXPIRED("登录凭证已到期"),
        AUTH_SESSION_INACTIVE("登录会话已失效"),
        AUTH_SESSION_REPLACED("当前账号已在其他会话登录"),
        AUTH_REFRESH_INVALID("会话无法续期，请重新登录"),
        AUTH_REFRESH_REUSED("会话已撤销，请重新登录"),
        AUTH_CSRF_INVALID("请求校验失败，请重试"),
        AUTH_SESSION_CHANGED("会话已切换，请在当前会话中操作"),
        AUTH_USERNAME_ALREADY_EXISTS("账号名已被使用，请更换账号名或直接登录"),
        AUTH_RATE_LIMITED("尝试次数过多，请稍后再试"),
        AUTH_SERVICE_UNAVAILABLE("认证服务暂不可用，请稍后再试");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        /** @return 可公开显示的提示文案 */
        public String message() {
            return message;
        }
    }

    private final Code code;
    private final long retryAfterSeconds;

    /** @param code 对外响应使用的稳定错误码 */
    public AuthFailure(Code code) {
        this(code, 0);
    }

    private AuthFailure(Code code, long retryAfterSeconds) {
        super(code.message());
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 创建带有固定窗口等待时间的注册限流失败。
     * @param retryAfterSeconds 允许重试前的等待秒数
     * @return 可映射到 HTTP 限流响应的认证失败
     */
    public static AuthFailure registrationRateLimited(long retryAfterSeconds) {
        return new AuthFailure(Code.AUTH_RATE_LIMITED, retryAfterSeconds);
    }

    /** @return 失败对应的稳定认证错误码 */
    public Code code() {
        return code;
    }

    /** @return 等待秒数，零表示没有重试时长 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
