package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** M2 wire contracts only; business evidence and rendering rules belong to validation. */
public final class EditDraftContracts {
    public static final int REQUEST_BYTES = 65536;
    public static final int RESULT_BYTES = 32768;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    private static final jakarta.validation.Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private EditDraftContracts() {}

    public enum Profile { LANDSCAPE_720, PORTRAIT_720 }
    public enum SavedStatus { SAVED }
    public record OutputCondition(@NotNull @CodePointLength(min=1,max=64) String output_key,
                                  @Min(1) Long duration_min_ms, @Min(1) Long duration_max_ms) {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore public boolean isOrdered() {
            return duration_min_ms == null || duration_max_ms == null || duration_min_ms <= duration_max_ms;
        }
    }
    public record Requirements(@NotNull @CodePointLength(max=500) String topic,
                               @NotNull @Size(min=1,max=100) List<@NotNull @CodePointLength(min=1,max=200) String> asset_ids,
                               @NotNull @Size(min=1,max=10) List<@NotNull @Valid OutputCondition> outputs,
                               @NotNull Profile profile, @JsonProperty(required=true) boolean subtitles_enabled,
                               @JsonProperty(required=true) boolean require_complete_subtitles,
                               @NotNull @Size(max=100) List<@NotNull @CodePointLength(min=1,max=256) String> forbidden_content) {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore public boolean isUnique() {
            return outputs == null || outputs.stream().filter(Objects::nonNull).map(OutputCondition::output_key).distinct().count() == outputs.size();
        }
    }
    public record Clip(@NotNull @CodePointLength(min=1,max=200) String asset_id,
                       @NotNull @Min(0) Long source_in_ms, @NotNull @Min(1) Long source_out_ms,
                       @NotNull @Size(min=1,max=50) List<@NotNull @CodePointLength(min=1,max=200) String> evidence_ids,
                       @NotNull @CodePointLength(min=1,max=500) String reason) {}
    public record Output(@NotNull @CodePointLength(min=1,max=64) String output_key,
                         @NotNull @CodePointLength(min=1,max=120) String title,
                         @NotNull @Size(min=1,max=100) List<@NotNull @Valid Clip> clips) {}
    public record Draft(@NotNull @CodePointLength(min=1,max=120) String title,
                        @NotNull @Size(min=1,max=10) List<@NotNull @Valid Output> outputs) {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore public boolean isUnique() {
            return outputs == null || outputs.stream().filter(Objects::nonNull).map(Output::output_key).distinct().count() == outputs.size();
        }
    }
    public record Input(@NotNull @Valid Draft draft, @NotNull @Valid Requirements requirements,
                        @NotNull @Pattern(regexp="[0-9a-f]{64}") String draft_hash,
                        @NotNull @Pattern(regexp="[0-9a-f]{64}") String requirements_hash) {}
    public record Issue(@NotNull String code, @NotNull String field, @NotNull String message,
                        @CodePointLength(min=1,max=64) String output_key, JsonNode actual, JsonNode expected, String hint) {}
    public record ValidationResult(@NotNull @CodePointLength(min=1,max=200) String validation_id,
                                   @JsonProperty(required=true) boolean valid,
                                   @NotNull @Pattern(regexp="[0-9a-f]{64}") String draft_hash,
                                   @NotNull @Pattern(regexp="[0-9a-f]{64}") String requirements_hash,
                                   @NotNull @Size(max=10) List<@NotNull @Min(0) Long> durations_ms,
                                   @NotNull List<@NotNull @Valid Issue> issues,
                                   @JsonProperty(required=true) boolean issues_truncated,
                                   @NotNull List<Map<String,Object>> warnings) {}
    public record SavedOutput(@NotNull @CodePointLength(min=1,max=64) String output_key,
                              @NotNull @CodePointLength(min=1,max=200) String output_id, @NotNull @Min(1) Long duration_ms) {}
    public record SavedResult(@NotNull SavedStatus status, @NotNull @CodePointLength(min=1,max=200) String plan_id,
                              @NotNull @CodePointLength(min=1,max=200) String revision_id, @NotNull @Min(1) Long revision_number,
                              @NotNull @Size(min=1,max=10) List<@NotNull @Valid SavedOutput> outputs) {}
    public record ProductionBinding(@NotNull @CodePointLength(min=1,max=200) String project_id,
                                    @NotNull @CodePointLength(min=1,max=200) String requirement_id,
                                    @NotNull @CodePointLength(min=1,max=200) String scope_id) {}
    public record SaveRequest(@NotNull @CodePointLength(min=1,max=200) String project_id,
                              @NotNull @CodePointLength(min=1,max=200) String requirement_id,
                              @NotNull @CodePointLength(min=1,max=200) String scope_id,
                              @NotNull @CodePointLength(min=1,max=200) String validation_id,
                              @NotNull @Pattern(regexp="[0-9a-f]{64}") String draft_hash,
                              @NotNull @Pattern(regexp="[0-9a-f]{64}") String requirements_hash) {}

    public static Input validation(JsonNode value) {
        var input = read(value, Input.class);
        var expected = input.requirements().outputs().stream().map(OutputCondition::output_key).collect(java.util.stream.Collectors.toSet());
        var actual = input.draft().outputs().stream().map(Output::output_key).collect(java.util.stream.Collectors.toSet());
        if (!expected.equals(actual)) throw new IllegalArgumentException("draft and requirements output_keys differ");
        return input;
    }

    public static <T> T read(JsonNode value, Class<T> type) {
        int limit = type == ValidationResult.class || type == SavedResult.class ? RESULT_BYTES : REQUEST_BYTES;
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > limit)
            throw new IllegalArgumentException("M2 payload exceeds byte limit");
        final T result;
        try { result = JSON.treeToValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid.getMessage(), invalid); }
        if (JSON.valueToTree(result).toString().getBytes(StandardCharsets.UTF_8).length > limit)
            throw new IllegalArgumentException("M2 payload exceeds byte limit");
        var errors = VALIDATOR.validate(result);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
        return result;
    }
}
