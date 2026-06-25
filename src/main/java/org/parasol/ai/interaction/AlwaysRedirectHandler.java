package org.parasol.ai.interaction;

import jakarta.ws.rs.core.Response.Status.Family;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import org.jboss.resteasy.reactive.client.handlers.RedirectHandler;

@Provider
public class AlwaysRedirectHandler implements ContextResolver<RedirectHandler> {
	@Override
	public RedirectHandler getContext(Class<?> type) {
		return response -> (response.getStatusInfo().getFamily() == Family.REDIRECTION) ?
		                   response.getLocation() :
		                   null;
	}
}
