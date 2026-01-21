package org.parasol.ai.testing.command;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class AIScoringCommandTest {
    @Test
    @Launch(exitCode = 2)
    public void testBasicLaunch(LaunchResult result) {
    }
}
