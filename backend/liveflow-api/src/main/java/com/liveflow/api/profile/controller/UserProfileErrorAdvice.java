package com.liveflow.api.profile.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.service.UserProfileService.InvalidProfileException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将资料请求的错误转换为客户端约定的公开响应。 */
@RestControllerAdvice(assignableTypes = UserProfileController.class)
class UserProfileErrorAdvice {

    @ExceptionHandler(InvalidProfileException.class)
    ResponseEntity<ProfileError> handleInvalidProfile(InvalidProfileException failure) {
        return invalidRequest(failure.fieldErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProfileError> handleInvalidBody() {
        return invalidRequest(Map.of());
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> handleAccount(AuthFailure failure) {
        return ResponseEntity.status(AuthHttpError.status(failure.code())).body(AuthHttpError.from(failure.code()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProfileError> handleStorageFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ProfileError("PROFILE_SERVICE_UNAVAILABLE", "资料服务暂不可用，请稍后再试", Map.of()));
    }

    private static ResponseEntity<ProfileError> invalidRequest(Map<String, String> fields) {
        return ResponseEntity.badRequest().body(new ProfileError("INVALID_REQUEST", "请检查请求字段", fields));
    }

    record ProfileError(String code, String message, @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Map<String, String> fieldErrors) {}
}
