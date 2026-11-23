package com.liveflow.api.auth;

import com.liveflow.core.auth.AuthFailure.Code;
import org.springframework.http.HttpStatus;

/** MVC 与 Spring Security 共用的认证错误响应正文及状态映射。 */
public record AuthHttpError(String code, String message) {

    /**
     * 将认证错误码映射为对外 HTTP 状态。
     *
     * @param code 业务层或安全过滤链给出的认证错误码
     * @return 认证接口约定的 HTTP 状态
     */
    public static HttpStatus status(Code code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case AUTH_INVALID_CREDENTIALS, AUTH_REQUIRED, AUTH_ACCESS_INVALID, AUTH_ACCESS_EXPIRED,
                    AUTH_SESSION_INACTIVE, AUTH_SESSION_REPLACED, AUTH_REFRESH_INVALID, AUTH_REFRESH_REUSED ->
                    HttpStatus.UNAUTHORIZED;
            case AUTH_CSRF_INVALID -> HttpStatus.FORBIDDEN;
            case AUTH_SESSION_CHANGED, AUTH_USERNAME_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case AUTH_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AUTH_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * 生成可公开返回的认证错误正文。
     *
     * @param code 业务层或安全过滤链给出的认证错误码
     * @return 包含稳定错误码和提示文案的响应正文
     */
    public static AuthHttpError from(Code code) {
        return new AuthHttpError(code.name(), code.message());
    }
}
