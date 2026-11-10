package kingrangE.DCBA.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@ControllerAdvice(annotations = Controller.class)
public class WebExceptionHandler {
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String missingResource(Model model) {
        model.addAttribute("message", "요청한 사용자 또는 문제를 찾을 수 없습니다.");
        return "error";
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String serverFailure(RuntimeException exception, Model model) {
        log.error("웹 요청 처리 중 서버 오류가 발생했습니다.", exception);
        model.addAttribute("message", "일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        return "error";
    }
}
