package org.parasol.intake.model;

/**
 * What an inbound email is, according to {@code EmailClassifierAgent}.
 * <p>
 * The label only decides the route when no claim matched: a matched claim always wins (see {@code EmailRouter}).
 * <p>
 * The {@link #description() descriptions} are the classifier's instructions. {@code EmailClassifierAgent}'s prompt
 * renders every constant with its description ({@code {triage:emailTypes}}), so the model sees them under every
 * provider. Relying on LangChain4j to append them isn't enough: only the OpenAI-style {@code EnumOutputParser} does,
 * while the Ollama provider sends the return type as a JSON schema with the constant names alone. They are a plain
 * field rather than a LangChain4j {@code @Description} so nothing reads them by reflection (which native images
 * wouldn't allow) and the OpenAI provider doesn't append a second copy.
 */
public enum EmailType {
	NEW_CLAIM("the email reports a new accident, damage, theft or loss"),
	CLAIM_FOLLOW_UP("the email asks about, or adds information to, an existing claim"),
	NOT_A_CLAIM("anything else, such as a question about a policy, a newsletter or spam");

	private final String description;

	EmailType(String description) {
		this.description = description;
	}

	/**
	 * What the type means, in a sentence. The classifier's prompt gives it to the model next to the constant name.
	 *
	 * @return the description, e.g. {@code "anything else, such as a question about a policy, a newsletter or spam"}
	 */
	public String description() {
		return this.description;
	}
}
