package kingrangE.DCBA.controller;

import kingrangE.DCBA.dto.api.ApiError;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import lombok.extern.slf4j.Slf4j;
import kingrangE.DCBA.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "요청을 처리하지 못했습니다." : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiError.of(message, exception.getStatusCode().value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler({TypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleInvalidFormat(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "요청한 값의 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<ApiError> handleMissingResource(EntityNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "요청한 사용자 또는 문제를 찾을 수 없습니다.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleFailure(Exception exception) {
        log.error("API 요청 처리 중 서버 오류가 발생했습니다.", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiError.of(message, status.value()));
    }
}
