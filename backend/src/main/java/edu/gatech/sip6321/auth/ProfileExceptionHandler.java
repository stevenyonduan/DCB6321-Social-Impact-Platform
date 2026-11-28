package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.social.SocialFailure;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice(assignableTypes = {ProfileController.class, ProfileAvatarController.class})
public class ProfileExceptionHandler {
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<SignupExceptionHandler.Error> invalid(Exception ignored) {
        return failure(ProfileFailure.invalid());
    }

    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> failure(ProfileFailure failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(SocialFailure.class)
    ResponseEntity<SignupExceptionHandler.Error> imageFailure(SocialFailure failure) {
        return switch (failure.code()) {
            case "INVALID_REQUEST" -> invalidImageRequest();
            case "REQUEST_TOO_LARGE" -> imageTooLarge();
            default -> error(failure.status(), failure.code(), failure.getMessage());
        };
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<SignupExceptionHandler.Error> invalidImageRequest() {
        return error(400, "INVALID_REQUEST", "Send exactly one image file.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<SignupExceptionHandler.Error> imageTooLarge() {
        return error(413, "REQUEST_TOO_LARGE", "Profile photo exceeds the 5 MiB limit.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<SignupExceptionHandler.Error> database() {
        return failure(ProfileFailure.database());
    }

    private ResponseEntity<SignupExceptionHandler.Error> error(
            int status, String code, String message) {
        var response =
                ResponseEntity.status(status)
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_JSON);
        if (status == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new SignupExceptionHandler.Error(code, message));
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
