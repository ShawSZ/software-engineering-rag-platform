package com.fast.knowledge.course;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fast.knowledge.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AccountRequests {
    private AccountRequests() { }
    public static final class Register {
        @JsonProperty @JsonDeserialize(using = Text.class) private String username;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @JsonDeserialize(using = Text.class) private String password;
        @JsonProperty @JsonDeserialize(using = Text.class) private String displayName;
        public Register() { }
        public String username() { return username; }
        public String password() { return password; }
        public String displayName() { return displayName; }
        @JsonSetter(value = "displayName", nulls = Nulls.FAIL)
        public void setDisplayName(String value) { displayName = value; }
        @Override public String toString() { return "Register[credentials redacted]"; }
    }
    public record Login(
            @JsonDeserialize(using = Text.class) String username,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @JsonDeserialize(using = Text.class) String password) {
        @Override public String toString() { return "Login[credentials redacted]"; }
    }
    public record Status(@JsonDeserialize(using = Text.class) String status) { }

    public static class Text extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }
    public static void username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,32}")) throw ApiException.invalid();
    }
    public static void password(String value, boolean registration) {
        if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw ApiException.invalid();
        int length = value.codePointCount(0, value.length());
        if (length < (registration ? 6 : 1) || length > 64
                || value.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiException.invalid();
    }
    public static String displayName(String value, String fallback) {
        if (value == null) return fallback;
        String name = value.strip();
        if (name.isEmpty() || !StandardCharsets.UTF_8.newEncoder().canEncode(name)
                || name.codePointCount(0, name.length()) > 64) throw ApiException.invalid();
        return name;
    }
    public static int status(String value) {
        if ("ENABLED".equals(value)) return 1;
        if ("DISABLED".equals(value)) return 0;
        throw ApiException.invalid();
    }
}
