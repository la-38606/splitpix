package com.luiz.splitpix.web;

import com.luiz.splitpix.common.ForbiddenException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * Invite-token transport for the browser (addendum 36.4).
 *
 * The token arrives once in the invite URL and is immediately exchanged for an
 * HttpOnly cookie scoped to that group's path; every later URL is token-free.
 * That keeps the only credential in the system out of browser history, out of
 * Referer headers, out of platform access logs, and away from the link-preview
 * bots that fetch any URL pasted into a chat app.
 *
 * SameSite=Lax is also the CSRF defense: the app carries no other credential
 * and no session, so blocking cross-site form posts is sufficient here.
 */
final class InviteCookie {

	private static final String NAME = "spx_convite";
	/** A shared browser is the norm for this product; the invite should not outlive the visit by much. */
	private static final int MAX_AGE_SECONDS = 60 * 60 * 12;

	private InviteCookie() {
	}

	static String path(UUID groupId) {
		return "/g/" + groupId;
	}

	static void set(HttpServletRequest request, HttpServletResponse response, UUID groupId, String token) {
		Cookie cookie = new Cookie(NAME, token);
		cookie.setHttpOnly(true);
		cookie.setPath(path(groupId));
		cookie.setMaxAge(MAX_AGE_SECONDS);
		cookie.setSecure(request.isSecure());
		cookie.setAttribute("SameSite", "Lax");
		response.addCookie(cookie);
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
