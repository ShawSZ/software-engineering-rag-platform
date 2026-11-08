package com.fast.knowledge.course;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.common.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CourseInputs {
    private CourseInputs() { }
    static void id(long value) { if (value < 1 || value > 9007199254740991L) throw ApiException.invalid(); }
    static String text(String value, int maximum) {
        if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw ApiException.invalid();
        String result = value.strip();
        if (result.isEmpty() || result.codePointCount(0, result.length()) > maximum) throw ApiException.invalid();
        return result;
    }
    static void optionalText(String value, int maximum) {
        if (value != null && (!StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.codePointCount(0, value.length()) > maximum)) throw ApiException.invalid();
    }
    public static class NonNegativeInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) || parser.getBigIntegerValue().signum()<0
                    || parser.getBigIntegerValue().bitLength()>31) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
