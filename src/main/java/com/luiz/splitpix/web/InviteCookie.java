package com.luiz.splitpix.web;

import com.luiz.splitpix.common.ForbiddenException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Invite-token transport for the browser (docs/design.md section 4.7).
 *
 * The token arrives once in the invite URL and is immediately exchanged for an
 * HttpOnly cookie scoped to that group's path; every later URL is token-free.
 * That keeps the only credential in the system out of browser history, out of
 * Referer headers, out of platform access logs, and away from the link-preview
 * bots that fetch any URL pasted into a chat app.
 *
 * SameSite=Lax is also the CSRF defense: the app carries no other credential
 * and no session, so blocking cross-site form posts is sufficient here. The
 * header is written via ResponseCookie rather than Cookie.setAttribute, whose
 * serialization is container-dependent — the CSRF attribute must not hinge on
 * which servlet container happens to be running.
 */
final class InviteCookie {

	private static final String NAME = "spx_convite";
	/** A shared browser is the norm for this product; the invite should not outlive the visit by much. */
	private static final Duration MAX_AGE = Duration.ofHours(12);

	private InviteCookie() {
	}

	static String path(UUID groupId) {
		return "/g/" + groupId;
	}

	static void set(HttpServletRequest request, HttpServletResponse response, UUID groupId, String token) {
		ResponseCookie cookie = ResponseCookie.from(NAME, token)
				.httpOnly(true)
				.path(path(groupId))
				.maxAge(MAX_AGE)
				.secure(request.isSecure())
				.sameSite("Lax")
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	/** The token for this group, or a 403 if the visitor never presented one. */
	static String require(HttpServletRequest request) {
		if (request.getCookies() != null) {
			for (Cookie cookie : request.getCookies()) {
				if (NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
					return cookie.getValue();
				}
			}
		}
		throw new ForbiddenException("INVALID_INVITE_TOKEN");
	}

}
