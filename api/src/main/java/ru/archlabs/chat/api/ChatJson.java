package ru.archlabs.chat.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.archlabs.chat.domain.ChatValidationException;

public final class ChatJson {
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ChatJson() {
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new ChatValidationException("Request body must not be blank");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new ChatValidationException("Malformed JSON: " + exception.getOriginalMessage());
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize response", exception);
        }
    }
}
