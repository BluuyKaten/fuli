package com.fuli.trade.practice;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Reject version coercion locally without changing other controllers' JSON handling. */
public final class ExpectedVersionDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return invalid(context);
        // getLongValue rejects integer overflow rather than truncating or wrapping it.
        long version = parser.getLongValue();
        return version >= 0 ? version : invalid(context);
    }

    @Override
    public Long getNullValue(DeserializationContext context) {
        return invalid(context);
    }

    @Override
    public Long getAbsentValue(DeserializationContext context) {
        return invalid(context);
    }

    private static Long invalid(DeserializationContext context) {
        return context.reportInputMismatch(Long.class, "expectedVersion must be a nonnegative JSON integer within long range");
    }
}
