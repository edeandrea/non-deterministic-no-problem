package org.parasol.intake.agent;

// Commented out, with ClaimsMailboxAgent's `extends MonitoredAgent` that it needs (agentMonitor()): see that interface's
// Javadoc and .tasks/05-gh216-agentic-email-intake/PLAN.md, 2c. Restore both together once langchain4j/langchain4j#6885
// and #6913 are released.
//
// import jakarta.enterprise.context.ApplicationScoped;
// import jakarta.enterprise.event.Observes;
//
// import io.quarkus.logging.Log;
// import io.quarkus.runtime.LaunchMode;
// import io.quarkus.runtime.StartupEvent;
//
// /**
//  * Keeps the agent monitor of {@link ClaimsMailboxAgent} from growing without bound. The monitor records every execution
//  * (inputs, outputs, the whole correspondence) in memory and, by default, never forgets one. That is what the dev UI's agent
//  * view reads, so dev mode keeps its default; every other mode retains none.
//  */
// @ApplicationScoped
// class AgentMonitorConfigurer {
// 	private final ClaimsMailboxAgent agent;
//
// 	AgentMonitorConfigurer(ClaimsMailboxAgent agent) {
// 		this.agent = agent;
// 	}
//
// 	void onStart(@Observes StartupEvent event) {
// 		if (LaunchMode.current() != LaunchMode.DEVELOPMENT) {
// 			this.agent.agentMonitor().setMaxRetainedSessions(0);
// 			Log.debug("The claims mailbox agent's monitor retains no executions");
// 		}
// 	}
// }
//