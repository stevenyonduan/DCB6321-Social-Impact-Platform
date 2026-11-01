package edu.gatech.sip6321.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProfileController.class)
public class ProfileExceptionHandler {
    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> failure(ProfileFailure failure) {
        var response = ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore());
        if (failure.status() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(
                new SignupExceptionHandler.Error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<SignupExceptionHandler.Error> unexpected(Exception ignored) {
        return ResponseEntity.internalServerError()
                .cacheControl(CacheControl.noStore())
                .body(
                        new SignupExceptionHandler.Error(
                                "INTERNAL_ERROR", "Profile could not be retrieved."));
    }
}
