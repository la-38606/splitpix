package com.luiz.splitpix.web;

import com.luiz.splitpix.common.ApiException;
import com.luiz.splitpix.common.ConflictException;
import com.luiz.splitpix.common.ForbiddenException;
import com.luiz.splitpix.common.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The page equivalent of the API error contract: the same codes resolve to the
 * same pt-BR messages and the same HTTP statuses, rendered as HTML instead of
 * JSON. The status matters even for a human-facing page — monitoring, caches
 * and browsers all treat a 200 error page as success.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(basePackageClasses = GroupPageController.class)
class PageExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(PageExceptionHandler.class);
	private static final Locale PT_BR = Locale.of("pt", "BR");

	private final MessageSource messageSource;

	PageExceptionHandler(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	@ExceptionHandler(ApiException.class)
	String handleApiException(ApiException e, Model model, HttpServletResponse response) {
		response.setStatus(statusOf(e).value());
		model.addAttribute("codigo", e.code());
		model.addAttribute("mensagem", messageSource.getMessage("error." + e.code(), null, PT_BR));
		return "erro";
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	String handleUnexpected(Exception e, Model model) {
		log.error("page failure", e);
		model.addAttribute("codigo", "INTERNAL_ERROR");
		model.addAttribute("mensagem", messageSource.getMessage("error.INTERNAL_ERROR", null, PT_BR));
		return "erro";
	}

	private static HttpStatus statusOf(ApiException e) {
		return switch (e) {
			case NotFoundException ignored -> HttpStatus.NOT_FOUND;
			case ForbiddenException ignored -> HttpStatus.FORBIDDEN;
			case ConflictException ignored -> HttpStatus.CONFLICT;
			default -> HttpStatus.BAD_REQUEST;
		};
	}

}
