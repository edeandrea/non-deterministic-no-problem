package ai.scoring.langfuse.rest;

public class LangfuseNotFoundException extends RuntimeException {
	public LangfuseNotFoundException(String message) {
		super(message);
	}
}
