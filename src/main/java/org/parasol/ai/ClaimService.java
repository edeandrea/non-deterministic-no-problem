package org.parasol.ai;

import org.parasol.model.claim.ClaimBotQuery;

import ai.scoring.rescore.RescoringOutputGuardrail;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ToolBox;
import io.quarkiverse.langchain4j.chatscopes.ChatRoute;
import io.quarkiverse.langchain4j.chatscopes.ChatScoped;
import io.quarkiverse.langchain4j.chatscopes.DefaultChatRoute;

@RegisterAiService(modelName = "parasol-chat", shouldThrowExceptionOnEventError = true)
@ChatScoped
@OutputGuardrails(RescoringOutputGuardrail.class)
public interface ClaimService {
    @SystemMessage("""
        You are a helpful, respectful and honest assistant named "Parasol Assistant".
        You will be given a claim summary, references to provide you with information, and a question. You must answer the question based as much as possible on this claim with the help of the references.
        Always answer as helpfully as possible, while being safe. Your answers should not include any harmful, unethical, racist, sexist, toxic, dangerous, or illegal content. Please ensure that your responses are socially unbiased and positive in nature.

        If a question does not make any sense, or is not factually coherent, explain why instead of answering something not correct. If you don't know the answer to a question, please don't share false information.

        You must answer in 4 sentences or less.

        Don't make up policy term limits by yourself
        """
    )
    @UserMessage("""
        Claim ID: {{query.claimId}}

        Policy Inception Date: {{query.inceptionDate}}

        Claim Summary:
        {{query.claim}}

        Question: {{query.query}}
    """)
    @DefaultChatRoute
    @ChatRoute("chat")
    @ToolBox(NotificationService.class)
	  String chat(ClaimBotQuery query);
//    Multi<String> chat(ClaimBotQuery query);
}
