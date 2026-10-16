package com.agenticvideo.business.agentrun;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed browser Agent input and public output. No scheduling or graph dependencies. */
public final class AgentRunContracts {
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String HASH = "[0-9a-f]{64}";
    public static final int PAYLOAD_BYTES = 1048576;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES).build();
    private static final jakarta.validation.Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private AgentRunContracts() {}

    public enum Mode { QUERY, DISCUSS, DRAFT }
    public enum Relation { INDEPENDENT, CONTINUE_PRODUCTION, NEW_PRODUCTION, ANSWER_QUESTION, CLARIFY }
    public enum Status { ANSWER, WAITING_INPUT, DRAFT_READY, INCOMPLETE, FAILED, CANCELLED }
    public enum Role { USER, ASSISTANT }
    public record Identity(@NotNull @Pattern(regexp=ID) String projectId,
                           @NotNull @Pattern(regexp=ID) String conversationId,
                           @NotNull @Pattern(regexp=ID) String messageId,
                           @NotNull @Pattern(regexp=ID) String turnId,
                           @NotNull @Pattern(regexp=ID) String taskId,
                           @NotNull @Pattern(regexp=ID) String threadId,
                           @NotNull @Pattern(regexp=ID) String executionId) {}
    public record HistoryMessage(@NotNull @Pattern(regexp=ID) String messageId, @NotNull Role role,
                                 @NotNull @CodePointLength(min=1,max=16000) String content,
                                 @NotNull @Size(max=100) List<@NotNull Map<String,JsonNode>> references) {}
    public record DraftBinding(@NotNull @Pattern(regexp=ID) String messageId,
                               @NotNull @Pattern(regexp=ID) String validationId,
                               @NotNull @Min(0) Long editGeneration,
                               @NotNull @Pattern(regexp=HASH) String draftHash,
                               @NotNull @Pattern(regexp=HASH) String requirementsHash) {}
    public record Production(@NotNull @Pattern(regexp=ID) String productionId,
                             @NotNull @Pattern(regexp=ID) String scopeId,
                             @NotNull @Pattern(regexp=ID) String threadId,
                             @NotNull @Min(0) Long editGeneration,
                             @NotNull Map<String,JsonNode> requirements,
                             @NotNull Map<String,@NotNull Map<String,JsonNode>> requirementsSources,
                             @Valid DraftBinding currentDraft, boolean frozen) {}
    public record PendingQuestion(@NotNull @Pattern(regexp=ID) String questionId,
                                  @NotNull @Pattern(regexp=ID) String taskId,
                                  @Pattern(regexp=ID) String productionId,
                                  @NotNull @Pattern(regexp=ID) String threadId,
                                  @NotNull @Pattern(regexp=ID) String executionId,
                                  @NotNull @CodePointLength(min=1,max=200) String waitId,
                                  @Min(0) Long editGeneration,
                                  @NotNull @CodePointLength(min=1,max=16000) String prompt) {
        @AssertTrue @JsonIgnore public boolean isProductionGenerationValid() {
            return (productionId == null) == (editGeneration == null);
        }
    }
    public record RunInput(@NotNull @Pattern(regexp="1\\.0") String schemaVersion,
                           @NotNull @Valid Identity identity,
                           @NotNull @CodePointLength(min=1,max=16000) String content,
                           @NotNull @Size(max=100) List<@NotNull @Valid HistoryMessage> history,
                           @NotNull @Size(max=100) List<@NotNull Map<String,JsonNode>> references,
                           @Valid Production currentProduction,
                           @NotNull @Size(max=100) List<@NotNull @Valid PendingQuestion> pendingQuestions) {}
    public record ParsedInput(@NotNull Mode mode, @NotNull Relation relation,
                              @Pattern(regexp=ID) String questionId, @NotNull Map<String,JsonNode> conditionsPatch) {
        @AssertTrue @JsonIgnore public boolean isRelationshipValid() {
            return relation != null && mode != null && conditionsPatch != null
                    && (relation == Relation.ANSWER_QUESTION) == (questionId != null)
                    && (mode == Mode.DRAFT || (conditionsPatch.isEmpty()
                        && relation != Relation.CONTINUE_PRODUCTION && relation != Relation.NEW_PRODUCTION));
        }
    }
    public record ScopeReceipt(@NotNull @Pattern(regexp=ID) String scopeId,
                               @NotNull @CodePointLength(min=1,max=200) String callId,
                               @NotNull @Size(max=100) List<@NotNull @Pattern(regexp=ID) String> evidenceIds) {}
    public record Failure(@NotNull @CodePointLength(min=1,max=64) String code,
                          @NotNull @CodePointLength(min=1,max=16000) String message, boolean recoverable) {}
    public record RunResult(@NotNull @Pattern(regexp="1\\.0") String schemaVersion,
                            @NotNull @Valid Identity identity, @NotNull Status status, @NotNull Mode mode,
                            @NotNull @CodePointLength(min=1,max=16000) String content,
                            @NotNull @Size(max=100) List<@NotNull @Valid ScopeReceipt> scopeReceipts,
                            @Pattern(regexp=ID) String productionId, @Min(0) Long editGeneration,
                            @Valid DraftBinding draft, @Valid PendingQuestion question, @Valid Failure failure) {
        @AssertTrue @JsonIgnore public boolean isResultShapeValid() {
            if (status == null || identity == null) return false;
            if ((productionId == null) != (editGeneration == null)) return false;
            if ((status == Status.DRAFT_READY) != (draft != null)) return false;
            if (draft != null && (mode != Mode.DRAFT || productionId == null || !Objects.equals(draft.editGeneration(), editGeneration))) return false;
            if ((status == Status.WAITING_INPUT) != (question != null)) return false;
            if ((status == Status.INCOMPLETE || status == Status.FAILED || status == Status.CANCELLED) != (failure != null)) return false;
            return question == null || (Objects.equals(question.taskId(), identity.taskId())
                    && Objects.equals(question.threadId(), identity.threadId()) && Objects.equals(question.executionId(), identity.executionId())
                    && Objects.equals(question.productionId(), productionId) && Objects.equals(question.editGeneration(), editGeneration));
        }
    }

    public static <T> T read(JsonNode value, Class<T> type) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > PAYLOAD_BYTES)
            throw new IllegalArgumentException("Agent payload exceeds byte limit");
        var pending = new ArrayDeque<JsonNode>();
        pending.add(value);
        while (!pending.isEmpty()) {
            var item = pending.removeLast();
            if (item.isFloatingPointNumber() || (item.isIntegralNumber() && !item.canConvertToLong()))
                throw new IllegalArgumentException("Agent JSON numbers must be signed 64-bit integers");
            if (item.isObject()) {
                var names = item.fieldNames();
                while (names.hasNext()) {
                    if (!names.next().chars().allMatch(character -> character < 128))
                        throw new IllegalArgumentException("Agent JSON field names must be ASCII");
                }
            }
            if (item.isContainerNode()) item.forEach(pending::add);
        }
        final T result;
        try { result = JSON.treeToValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid.getMessage(), invalid); }
        var errors = VALIDATOR.validate(result);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
        return result;
    }
}
