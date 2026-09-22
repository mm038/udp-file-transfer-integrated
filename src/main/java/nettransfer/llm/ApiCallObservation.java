package nettransfer.llm;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional, credential-free metadata for one actual API HTTP attempt. Latency measures HTTP only,
 * never UDP transfer time. Nullable metadata was absent, malformed, or unsafe to retain; null token
 * usage is not zero. No request/response bodies, headers, provider diagnostics, or reasoning text.
 */
public record ApiCallObservation(UUID requestId, String requestedModel, String returnedModel,
                                 int attempt, Instant startedAt, long apiLatencyMillis,
                                 Integer httpStatus, GptException.Code failureCode,
                                 String responseStatus, Long inputTokens, Long outputTokens,
                                 Long totalTokens, Long cachedInputTokens, Long reasoningTokens) { }
