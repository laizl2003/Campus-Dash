package com.campusdash.worker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AutoSettleConsumerTest {

    @Test
    void parsesUnquotedNumericErrandIdFromMqPayload() throws Exception {
        long errandId = assertDoesNotThrow(() ->
                extractLong("{\"errandId\": 215872245673758720}", "errandId"));

        assertEquals(215872245673758720L, errandId);
    }

    private static long extractLong(String json, String field) throws Exception {
        Method method = AutoSettleConsumer.class.getDeclaredMethod("extractLong", String.class, String.class);
        method.setAccessible(true);
        try {
            return (long) method.invoke(null, json, field);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
