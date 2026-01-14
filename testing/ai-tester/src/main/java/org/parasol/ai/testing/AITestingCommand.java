package org.parasol.ai.testing;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import org.parasol.ai.testing.domain.jpa.Interaction;
import org.parasol.ai.testing.service.AiTestingService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "ai-testing", mixinStandardHelpOptions = true)
public class AITestingCommand implements Runnable {
	@Option(
      names = { "-s", "--start" },
      required = true,
      description = "The start for the testing range"
  )
	Instant start;

	@Option(
      names = { "-e", "--end" },
      description = "The end for the testing range (default: now)"
  )
	Optional<Instant> end;

	@Option(
		names = { "-u", "--api-uri" },
		required = true,
		description = "The endpoint URI of the API providing the interactions under test"
	)
	URI apiUri;

	private final AiTestingService aiTestingService;

	public AITestingCommand(AiTestingService aiTestingService) {
		this.aiTestingService = aiTestingService;
	}

	@Override
  public void run() {
		System.out.println("==========================================");
		System.out.println("Stored interactions:");
		this.aiTestingService.getAllStoredInteractions().forEach(this::printInteraction);

		var interactionsFromSource = this.aiTestingService.getSuccessfulInteractions(this.start, this.end, this.apiUri);

		System.out.println("==========================================");
		System.out.println("Interactions from source:");
		interactionsFromSource.forEach(this::printInteraction);
		this.aiTestingService.storeInteractions(interactionsFromSource);

		System.out.println("==========================================");
		System.out.println("Stored interactions (again):");
		this.aiTestingService.getAllStoredInteractions().forEach(this::printInteraction);
  }

	private void printInteraction(Interaction interaction) {
		System.out.println("===============================");
		System.out.println("SUCCESSFUL INTERACTION:");
		System.out.println("System Message:\n%s".formatted(interaction.getSystemMessage()));
		System.out.println("\nUser Message:\n%s".formatted(interaction.getUserMessage()));
		System.out.println("\nStatus:\n%s".formatted(interaction.getResult()));
	}
}
