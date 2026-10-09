package com.chartering.dto;

import com.chartering.service.parser.FewShotRetriever;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The examples the parser would insert for one email, and the model they were retrieved for.
 *
 * <p>The turns are the parser's own, not a rendering for the harness: they are what a production
 * request carries between the system prompt and the question, so a score measured against them is
 * a score of production. {@code model} is there so a harness can notice that the vectors it is
 * comparing against came from a different model than the one it is evaluating.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisFewShotResponse(String model, List<FewShotRetriever.Example> examples) {
}
