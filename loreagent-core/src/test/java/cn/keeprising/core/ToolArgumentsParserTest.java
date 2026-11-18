package cn.keeprising.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolArgumentsParserTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["op", "nums"],
              "properties": {
                "op": { "type": "string", "enum": ["add", "mul"] },
                "nums": { "type": "array", "items": { "type": "integer" } },
                "precision": { "type": "integer" }
              },
              "additionalProperties": false
            }
            """;

    private final ToolArgumentsParser parser = new ToolArgumentsParser();

    @Test
    void shouldValidateValidArgumentsAndIgnoreUnsupportedSchemaKeywords() {
        JsonNode node = parser.parseAndValidate(
                "{\"op\":\"add\",\"nums\":[1,2,3],\"extra\":\"ignored\"}", SCHEMA);

        assertThat(node.get("op").asText()).isEqualTo("add");
        assertThat(node.get("nums").size()).isEqualTo(3);
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> parser.parseAndValidate("{not-json", SCHEMA))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void shouldRejectMissingRequiredFieldWrongEnumAndBadArrayItemType() {
        assertThatThrownBy(() -> parser.parseAndValidate("{\"op\":\"add\"}", SCHEMA))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("nums");

        assertThatThrownBy(() -> parser.parseAndValidate("{\"op\":\"sub\",\"nums\":[1]}", SCHEMA))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("enum");

        assertThatThrownBy(() -> parser.parseAndValidate("{\"op\":\"add\",\"nums\":[1, 1.5]}", SCHEMA))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));
    }
}
