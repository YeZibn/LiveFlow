package com.liveflow.api.auth.controller;

import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 将认证控制器内的失败转换为统一的公开错误格式。 */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthErrorAdvice {

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> handleFailure(AuthFailure failure) {
        return respond(failure.code());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthHttpError> handleInvalidBody() {
        return respond(INVALID_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<AuthHttpError> handleDataUnavailable() {
        return respond(AUTH_SERVICE_UNAVAILABLE);
    }

    private static ResponseEntity<AuthHttpError> respond(AuthFailure.Code code) {
        return ResponseEntity.status(AuthHttpError.status(code)).body(AuthHttpError.from(code));
    }
}
