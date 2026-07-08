package ai.scoring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.websockets.next.BasicWebSocketConnector;

import ai.scoring.it.DriftDetectionTest.DriftTestProfile;
import ai.scoring.it.DriftDetectionTests.Query.QueryData;
import io.quarkiverse.langchain4j.chatscopes.websocket.WebsocketChatRoutes;

@DriftDetectionTest
@TestProfile(DriftTestProfile.class)
public class DriftDetectionTests {
	record Query(QueryData query) {
		record QueryData(long claimId, String claim, String query, LocalDate inceptionDate) {}
	}

	@ConfigProperty(name = "quarkus.http.test-port")
	int testPort;

	@Test
	void noDrift() {
		var messages = new ArrayList<String>();
		var errors = new ArrayList<String>();
		var connector = BasicWebSocketConnector.create()
		                                       .baseUri("http://localhost:%d/_chat/routes".formatted(this.testPort));

		var sessionBuilder = WebsocketChatRoutes.newClient(connector)
		                                        .builder()
		                                        .messageHandler(message -> {
			                                        Log.infof("Got message: %s", message);
			                                        messages.add(message);
		                                        })
		                                        .errorHandler(error -> {
			                                        Log.warnf("Got error: %s", error);
			                                        errors.add(error);
		                                        });

		try (var session = sessionBuilder.connect("chat")) {
			session.chat(new Query(new QueryData(1, """
				On January 2nd, 1955, at around 3:30 PM, a car accident occurred at the intersection of Colima Road and Azusa Avenue in Hill Vallet. The involved parties were Marty McFly, driving a silver Delorean DMC-12 (OUTA-TIME), and Biff Tanner in a blue Type 2 Volkswagen Bus (BIF-RULZ).
				
				Marty was heading south on Colima Road when Biff failed to stop at the red traffic signal on Asuza Avenue, causing a collision with Marty's vehicle. Both drivers exchanged information and took photos of the accident scene, which included damages to the front driver and passenger side of Marty's Delorean DMC-12 and the front driver's side of Biff's Volkswagen Bus. No injuries were reported.
				
				Marty has attached necessary documents, such as photos, a police report, and an estimate for repair costs, to his email. He requests prompt attention to the claim and is available at (916) 555-4385 or marty.mcfly@email.com for any additional information or documentation needed.
				""", "Who is at fault?", LocalDate.of(1955, 9, 30))));

			await().atMost(Duration.ofSeconds(15))
			       .pollInterval(Duration.ofSeconds(2))
			       .pollDelay(Duration.ofSeconds(2))
			       .untilAsserted(() -> {
				       assertThat(errors).isEmpty();

				       assertThat(messages).isNotEmpty()
				                           .singleElement()
				                           .asInstanceOf(STRING)
				                           .doesNotContainIgnoringCase("drift detected");
			       });
		}
	}
}
