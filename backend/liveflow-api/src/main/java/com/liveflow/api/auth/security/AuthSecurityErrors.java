package com.liveflow.api.auth.security;

import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 在 MVC 之前为 Bearer 或 CSRF 校验错误写出同款认证错误。 */
@Component
public class AuthSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    /** @param json 应用统一配置的 JSON 序列化器 */
    public AuthSecurityErrors(JsonMapper json) {
        this.json = json;
    }

    /**
     * 写出认证失败响应。
     * @param request 当前 HTTP 请求
     * @param response 写入状态、响应头与错误正文的目标
     * @param exception Spring Security 捕获的认证失败
     * @throws IOException 响应无法写入时抛出
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        AuthFailure.Code code = AUTH_REQUIRED;
        if (exception instanceof AuthFailure failure) {
            code = failure.code();
        } else if (exception instanceof InvalidBearerTokenException) {
            code = AUTH_ACCESS_INVALID;
            // Spring 会包装 JWT 校验异常。这里保留「已到期」语义，供客户端决定是否续期。
            if (exception.getCause() instanceof JwtValidationException validation
                    && validation.getErrors().stream().anyMatch(e -> AUTH_ACCESS_EXPIRED.name().equals(e.getErrorCode()))) {
                code = AUTH_ACCESS_EXPIRED;
            }
        }
        if (AuthHttpError.status(code) == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, code == AUTH_REQUIRED
                    ? "Bearer" : "Bearer error=\"invalid_token\"");
        }
        write(response, code);
    }

    /**
     * 区分 CSRF 拒绝与一般访问拒绝。
     * @param request 当前 HTTP 请求
     * @param response HTTP 响应
     * @param exception 校验失败的原因
     * @throws IOException 响应无法写入时抛出
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (exception instanceof CsrfException) {
            write(response, AUTH_CSRF_INVALID);
        } else {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private void write(HttpServletResponse response, AuthFailure.Code code) throws IOException {
        response.setStatus(AuthHttpError.status(code).value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), AuthHttpError.from(code));
    }
}
