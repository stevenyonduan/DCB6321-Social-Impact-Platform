package edu.gatech.sip6321.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public class SignupExceptionHandler {
    public record Error(String code, String message) {}

    @ExceptionHandler(SignupFailure.class)
    ResponseEntity<Error> signupFailure(SignupFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<Error> invalid(Exception ignored) {
        return signupFailure(SignupFailure.invalid());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Error> mediaType(Exception ignored) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Error> method(Exception ignored) {
        return error(405, "METHOD_NOT_ALLOWED", "Use POST for signup.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception ignored) {
        return error(500, "INTERNAL_ERROR", "Registration could not be completed.");
    }

    private ResponseEntity<Error> error(int status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new Error(code, message));
    }
}
