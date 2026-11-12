package edu.gatech.sip6321.opportunity;

import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.auth.SignupExceptionHandler;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = OpportunityController.class)
public class OpportunityExceptionHandler {
    @ExceptionHandler(OpportunityFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> failure(OpportunityFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> auth(ProfileFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<SignupExceptionHandler.Error> invalid(Exception ignored) {
        return failure(OpportunityFailure.invalid());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<SignupExceptionHandler.Error> mediaType(Exception ignored) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<SignupExceptionHandler.Error> method(Exception ignored) {
        return error(405, "METHOD_NOT_ALLOWED", "Use a supported opportunity method.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<SignupExceptionHandler.Error> database(Exception ignored) {
        return error(
                503, "OPPORTUNITY_UNAVAILABLE", "Opportunity access is temporarily unavailable.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<SignupExceptionHandler.Error> unexpected(Exception ignored) {
        return error(500, "INTERNAL_ERROR", "Opportunity access could not be completed.");
    }

    private ResponseEntity<SignupExceptionHandler.Error> error(
            int status, String code, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new SignupExceptionHandler.Error(code, message));
    }
}
