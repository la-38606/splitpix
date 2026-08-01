package com.luiz.splitpix.web;

import com.luiz.splitpix.common.ApiException;
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
 * same pt-BR messages, rendered as HTML instead of JSON.
 *
 * Stale suggestions are normal, not exceptional — an expense recorded by anyone
 * else invalidates the payment you were looking at (design doc 12.6) — so a
 * conflict returns the user to the group page with an explanation and freshly
 * computed numbers rather than to an error screen.
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
	String handleApiException(ApiException e, Model model) {
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

}
