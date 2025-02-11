package org.parasol.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.logging.Log;

@Path("/hello")
@Produces(MediaType.APPLICATION_JSON)
public class HelloResource {
	record HelloObject(String message, String description) {}

	@GET
	public HelloObject getHello() {
		Log.infof("Inside %s.getHello()", HelloObject.class.getSimpleName());
		return new HelloObject("Hello World", "This is a hello world message");
	}
}
