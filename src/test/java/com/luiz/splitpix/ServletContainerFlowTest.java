package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one test that needs a real servlet container. MockMvc never rewrites
 * URLs, so it stayed green while real Tomcat encoded the session id into the
 * first PRG redirect of a browser session ("/g/{id};jsessionid=..."). That
 * path fails RFC 6265 matching against the invite cookie's "/g/{id}" path,
 * so the redirected GET arrived without the credential and 403'd. Fixed by
 * cookie-only session tracking (application.properties); pinned here against
 * the embedded container itself. JDK HttpClient on purpose: it follows no
 * redirects by default, so the Location header stays observable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServletContainerFlowTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void firstFormPostOfASession_redirectsToAPathTheInviteCookieStillMatches() throws Exception {
		HttpClient client = HttpClient.newHttpClient();

		HttpResponse<String> created = client.send(HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/v1/groups"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"groupName\": \"Sessão\", \"creatorName\": \"Luiz\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode group = objectMapper.readTree(created.body());
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();

		// The flash message forces session creation on this request — the
		// exact condition that used to trigger URL rewriting.
		HttpResponse<String> redirect = client.send(HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/g/" + groupId + "/participantes"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Cookie", "spx_convite=" + token)
				.POST(HttpRequest.BodyPublishers.ofString("displayName=Ana"))
				.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(redirect.statusCode()).isEqualTo(302);
		String location = redirect.headers().firstValue("Location").orElse("");
		assertThat(location).doesNotContain(";jsessionid").endsWith("/g/" + groupId);
	}

}
