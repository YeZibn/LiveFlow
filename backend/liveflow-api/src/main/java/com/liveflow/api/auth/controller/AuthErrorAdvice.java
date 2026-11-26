package com.liveflow.api.auth.controller;

import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 将认证控制器内的失败转换为统一的公开错误格式。 */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthErrorAdvice {
    private final Counter replayRejected;
    private final Counter dependencyUnavailable;

    AuthErrorAdvice(MeterRegistry meters) {
        replayRejected = meters.counter("liveflow.auth.refresh.reused");
        dependencyUnavailable = meters.counter("liveflow.auth.dependency.unavailable");
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> handleFailure(AuthFailure failure) {
        if (failure.code() == AUTH_REFRESH_REUSED) replayRejected.increment();
        if (failure.code() == AUTH_SERVICE_UNAVAILABLE) dependencyUnavailable.increment();
        var response = ResponseEntity.status(AuthHttpError.status(failure.code()));
        if (failure.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()));
        }
        return response.body(AuthHttpError.from(failure.code()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthHttpError> handleInvalidBody() {
        return respond(INVALID_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<AuthHttpError> handleDataUnavailable() {
        dependencyUnavailable.increment();
        return respond(AUTH_SERVICE_UNAVAILABLE);
    }

    private static ResponseEntity<AuthHttpError> respond(AuthFailure.Code code) {
        return ResponseEntity.status(AuthHttpError.status(code)).body(AuthHttpError.from(code));
    }
}
