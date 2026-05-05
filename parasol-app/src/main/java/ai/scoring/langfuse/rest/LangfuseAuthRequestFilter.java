package ai.scoring.langfuse.rest;

import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import ai.scoring.langfuse.config.LangfuseConfig;

@ApplicationScoped
public class LangfuseAuthRequestFilter implements ClientRequestFilter {
	private final LangfuseConfig config;

	public LangfuseAuthRequestFilter(LangfuseConfig config) {
		this.config = config;
	}

	@Override
	public void filter(ClientRequestContext requestContext) {
		var credentials = "%s:%s".formatted(this.config.publicKey(), this.config.secretKey());
		var authHeader = "Basic %s".formatted(Base64.getEncoder().encodeToString(credentials.getBytes()));
		requestContext.getHeaders().addFirst(HttpHeaders.AUTHORIZATION, authHeader);
	}
}
