package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.version.SkillDeletedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Turns exceptions into the one error envelope of §4.1.
 *
 * <p>The point of doing this centrally is that a client should never have to handle two shapes.
 * Spring's own errors — an unsupported method, an unreadable body — would otherwise come back as
 * RFC 9457 {@code problem+json} while ours come back as {@code {"error": …}}, and a client would
 * need to know which is which before it could read a status code.
 *
 * <p>What is <em>not</em> done here is the 404-for-a-private-skill decision. That is a domain
 * conclusion reached in M4, and by the time control arrives here the answer is already a plain
 * "not found" — see {@link SkillsController}. An exception handler is the wrong place for it,
 * because by then the information needed to decide is gone.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A rejected upload. Carries every problem found, so one round trip is enough to fix it. */
    @ExceptionHandler(IngestException.class)
    ResponseEntity<ApiError> onIngestException(IngestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD, e.getMessage(), e.details()));
    }

    /**
     * A publish to the name of a soft-deleted skill (§4.3 gives restoring its own endpoint).
     *
     * <p>400 rather than 409: the request is well-formed and the conflict is real, but the only
     * answer the API offers is "restore it first", which the message says. A distinct status would
     * invite clients to branch on a condition that has exactly one recovery.
     */
    @ExceptionHandler(SkillDeletedException.class)
    ResponseEntity<ApiError> onSkillDeletedException(SkillDeletedException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * A query this API does not perform: a limit below one, or a cursor it cannot read.
     *
     * <p>A 400 rather than a repaired request. A cursor that is silently ignored restarts the
     * listing from the beginning, which a client paginating a large result set sees as an infinite
     * loop — and it has no way to tell that from a listing that happens to shrink.
     */
    @ExceptionHandler(InvalidSearchRequestException.class)
    ResponseEntity<ApiError> onInvalidSearchRequest(InvalidSearchRequestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * A query parameter that does not fit its type — {@code limit=abc}, say.
     *
     * <p>Handled by name rather than left to the catch-all, which looks redundant and is not:
     * Spring 7's {@code MethodArgumentTypeMismatchException} no longer implements
     * {@link ErrorResponse}, so the catch-all would answer 500 for what is plainly a bad request.
     * That was measured, not assumed — the first version of the search endpoint returned
     * {@code internal_error} for {@code limit=abc}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST,
                        "the '" + e.getName() + "' parameter is not a valid value"));
    }

    /**
     * The body exceeded the multipart ceiling, so it was refused before it was ever buffered.
     *
     * <p>Handled explicitly because the container's own answer is a bare 413 and the caller's next
     * question is always "how big may it be" — the ceiling is not the skill limit, and a caller who
     * reads 413 as "my skill is too big" would go and shrink the wrong thing.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> onMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD,
                        "the request body is too large to be accepted", "file", "body_too_large"));
    }

    /** The request had no {@code file} part at all — the most common mistake against this API. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> onMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD,
                        "the request is missing the '" + e.getRequestPartName() + "' part",
                        e.getRequestPartName(), "missing"));
    }

    /**
     * Everything else.
     *
     * <p>Spring's own web exceptions implement {@link ErrorResponse} and know their status; those
     * keep it, and only the body is re-shaped. Their messages are replaced with one of ours —
     * framework messages describe the framework's view of the request and have a habit of naming
     * internals, whereas the status is the part a client should act on.
     *
     * <p>Anything that is not one of those is a bug on this side: it is logged with its stack trace
     * and answered as a 500 whose body says nothing about what broke, because the caller can do
     * nothing with that and it is exactly the kind of detail that should not leave the process.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> onAnythingElse(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) {
                log.error("exception carried an unknown status {}", errorResponse.getStatusCode(), e);
                return internalError();
            }
            log.debug("request rejected with {}", status, e);
            return ResponseEntity.status(status)
                    .body(ApiError.of(codeFor(status), "the request could not be handled"));
        }

        log.error("unhandled exception", e);
        return internalError();
    }

    private static ResponseEntity<ApiError> internalError() {
        return ResponseEntity.internalServerError()
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "an unexpected error occurred"));
    }

    private static ErrorCode codeFor(HttpStatus status) {
        return status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    }
}
