package edu.gatech.sip6321.social;

import edu.gatech.sip6321.auth.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.*;

@RestControllerAdvice(assignableTypes = SocialController.class)
public class SocialExceptionHandler {
    @ExceptionHandler(SocialFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> failure(SocialFailure f) {
        return error(f.status(), f.code(), f.getMessage());
    }

    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> auth(ProfileFailure f) {
        return error(f.status(), f.code(), f.getMessage());
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        MultipartException.class
    })
    ResponseEntity<SignupExceptionHandler.Error> invalid(Exception e) {
        return failure(SocialFailure.invalid());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<SignupExceptionHandler.Error> large(Exception e) {
        return failure(SocialFailure.large());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<SignupExceptionHandler.Error> media(Exception e) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<SignupExceptionHandler.Error> method(Exception e) {
        return error(405, "METHOD_NOT_ALLOWED", "Use a supported social method.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<SignupExceptionHandler.Error> database(Exception e) {
        return error(503, "SOCIAL_UNAVAILABLE", "Social access is temporarily unavailable.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<SignupExceptionHandler.Error> unexpected(Exception e) {
        return error(500, "INTERNAL_ERROR", "Social request could not be completed.");
    }

    private ResponseEntity<SignupExceptionHandler.Error> error(
            int status, String code, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new SignupExceptionHandler.Error(code, message));
    }
}
