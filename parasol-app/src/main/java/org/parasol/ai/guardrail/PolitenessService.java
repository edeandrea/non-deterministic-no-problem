package org.parasol.ai.guardrail;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoRetrievalAugmentorSupplier;

@RegisterAiService(modelName = "politeness", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)
public interface PolitenessService {
	@SystemMessage("""
		You are a detector that determines whether or not a message is polite and respectful.
	
		Simply try to detect whether the string is polite or not.
		
		Do not tell me what you are thinking.
		
		Return a boolean value, either true or false, where true means it is polite and respectful, and false means it is certainly not polite or respectful.
		
		Do not return anything else. Do not even return a newline or a leading field. Only a single boolean value.
		
		Here are some examples:
		
		Example 1:
		query: Hello from Parasol Insurance
		true
		
		Example 2:
		query: Dear John, thank you for filing a claim with us.
		true
		
		Example 3:
		query: Hello. Your claim has been updated.
		true
		
		Example 4:
		query: You dummy, you should have been paying better attention!
		false
		
		Example 5:
		query: You should be more careful while you are driving.
		false
	""")
	boolean isPolite(@UserMessage String query);
}
