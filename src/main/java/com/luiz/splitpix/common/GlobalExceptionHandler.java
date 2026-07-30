package com.luiz.splitpix.common;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to the {code, message} error contract of section 16.
 * Codes are stable English identifiers; messages are pt-BR (section 21).
 * Tests assert on codes, never on localized message text.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private static final Locale PT_BR = Locale.of("pt", "BR");

	private final MessageSource messageSource;

	public GlobalExceptionHandler(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
		return respond(HttpStatus.NOT_FOUND, e.code());
	}

	@ExceptionHandler(ForbiddenException.class)
	public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
		return respond(HttpStatus.FORBIDDEN, e.code());
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ErrorResponse> handleConflict(ConflictException e) {
		return respond(HttpStatus.CONFLICT, e.code());
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
		return respond(HttpStatus.BAD_REQUEST, e.code());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
		return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleIntegrityViolation(DataIntegrityViolationException e) {
		// Database constraints are the second line of defense; anything the
		// services did not pre-validate surfaces as a generic conflict.
		log.warn("constraint violation: operation rejected by database");
		return respond(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION");
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ErrorResponse> handleDatabase(DataAccessException e) {
		log.error("database failure", e);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
	}

	private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code) {
		String message = messageSource.getMessage("error." + code, null, "Erro inesperado.", PT_BR);
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

}
