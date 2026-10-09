package org.parasol.intake.model;

import dev.langchain4j.model.output.structured.Description;

/**
 * What an inbound email is, according to {@code EmailClassifierAgent}.
 * <p>
 * The label only decides the route when no claim matched: a matched claim always wins (see {@code EmailRouter}).
 * <p>
 * The {@link Description}s are the classifier's instructions: {@code EmailClassifierAgent} returns this enum, so
 * LangChain4j appends every constant with its description to the request ({@code EnumOutputParser}), and the prompt
 * doesn't list them itself.
 */
public enum EmailType {
	@Description("the email reports a new accident, damage, theft or loss")
	NEW_CLAIM,

	@Description("the email asks about, or adds information to, an existing claim")
	CLAIM_FOLLOW_UP,

	@Description("anything else, such as a question about a policy, a newsletter or spam")
	NOT_A_CLAIM
}
