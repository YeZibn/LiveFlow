package com.liveflow.api.room.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.service.RoomService.InvalidDirectoryQueryException;
import com.liveflow.core.room.service.RoomService.InvalidTitleException;
import com.liveflow.core.room.service.RoomService.RoomAlreadyExistsException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/** 将房间接口的请求、认证及存储异常映射为公开错误。 */
@RestControllerAdvice(assignableTypes = RoomController.class)
class RoomErrorAdvice {

    @ExceptionHandler({RoomController.InvalidRoomRequestException.class, InvalidDirectoryQueryException.class,
            HttpMessageNotReadableException.class, JacksonException.class})
    ResponseEntity<RoomError> invalidRequest() {
        return ResponseEntity.badRequest().body(new RoomError("INVALID_REQUEST", "请检查请求字段", Map.of()));
    }

    @ExceptionHandler(InvalidTitleException.class)
    ResponseEntity<RoomError> invalidTitle(InvalidTitleException failure) {
        return ResponseEntity.badRequest().body(
                new RoomError("INVALID_REQUEST", "请检查请求字段", Map.of("title", failure.getMessage())));
    }

    @ExceptionHandler(RoomController.RoomNotFoundException.class)
    ResponseEntity<RoomError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new RoomError("ROOM_NOT_FOUND", "房间不存在", Map.of()));
    }

    @ExceptionHandler(RoomAlreadyExistsException.class)
    ResponseEntity<RoomError> alreadyExists(RoomAlreadyExistsException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new RoomError("ROOM_ALREADY_EXISTS", failure.getMessage(), Map.of()));
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> accountUnavailable(AuthFailure failure) {
        return ResponseEntity.status(AuthHttpError.status(failure.code())).body(AuthHttpError.from(failure.code()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<RoomError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new RoomError("ROOM_SERVICE_UNAVAILABLE", "直播间服务暂不可用，请稍后再试", Map.of()));
    }

    record RoomError(String code, String message, @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Map<String, String> fieldErrors) {}
}
