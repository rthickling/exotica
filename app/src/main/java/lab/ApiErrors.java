package lab;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import static lab.JsonSupport.fields;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) {
        return response(e.status, e.code, e.getMessage());
    }

    @ExceptionHandler({ DataIntegrityViolationException.class, OptimisticLockingFailureException.class })
    ResponseEntity<?> conflict(Exception e) {
        return response(409, "PERSISTENCE_CONFLICT",
                "A competing booking or repeated unique event was rejected by database constraints/versioning; reread the enquiry");
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class })
    ResponseEntity<?> invalid(Exception e) {
        return response(400, "INVALID_REQUEST", "Request is missing required fields or has invalid values/types");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("event=unhandled_failure correlationId={}",
                CorrelationFilter.current(), e);
        return response(500, "INTERNAL_ERROR", "Unexpected local application failure; inspect correlated logs");
    }

    private ResponseEntity<?> response(int status, String code, String message) {
        return ResponseEntity.status(status)
                .body(fields("error", code, "message", message, "correlationId", CorrelationFilter.current()));
    }
}
