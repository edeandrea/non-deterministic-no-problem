package org.parasol.ai.testing.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;

import io.quarkus.arc.properties.IfBuildProperty;

public class ScoringModelConfig {
	@Produces
	@ApplicationScoped
	@IfBuildProperty(name = "ai-testing.use-onnx-model", stringValue = "true")
	public ScoringModel scoringModel() {
		var modelsDir = "%s/models/Qwen3-rerank-8B-onnx".formatted(System.getProperty("user.home"));
		var pathToModel = "%s/model.onnx".formatted(modelsDir);
		var pathToTokenizer = "%s/tokenizer.json".formatted(modelsDir);

		return new OnnxScoringModel(pathToModel, pathToTokenizer);
	}
}
