package edu.gatech.sip6321.account.interests;

import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.auth.SignupExceptionHandler.Error;
import edu.gatech.sip6321.organization.OrganizationFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = InterestController.class)
class InterestExceptionHandler {
    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<Error> profile(ProfileFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(OrganizationFailure.class)
    ResponseEntity<Error> organization(OrganizationFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(UnknownInterestException.class)
    ResponseEntity<Error> unknownInterest() {
        return error(400, "UNKNOWN_INTEREST", "Choose interests from the current catalog.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Error> invalidRequest() {
        return error(400, "INVALID_REQUEST", "Send an interestIds array of interest UUIDs.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> invalidOrganizationId() {
        return error(400, "INVALID_REQUEST", "Use a valid organization ID.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Error> mediaType() {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Error> database() {
        return error(503, "INTERESTS_UNAVAILABLE", "Interests are temporarily unavailable.");
    }

    private ResponseEntity<Error> error(int status, String code, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new Error(code, message));
    }
}
