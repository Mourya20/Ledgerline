package com.ledgerline.ledgerline.auth.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MaxUtf8BytesValidatorTest {

    private MaxUtf8BytesValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MaxUtf8BytesValidator();
        // Default max is 72
        validator.initialize(new MaxUtf8Bytes() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return MaxUtf8Bytes.class;
            }

            @Override
            public String message() {
                return "Exceeds max bytes";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }

            @Override
            public int max() {
                return 72;
            }
        });
    }

    @Test
    void nullAndEmptyStringAreValid() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
    }

    @Test
    void asciiWithinLimitIsValid() {
        assertTrue(validator.isValid("a".repeat(72), null));
        assertTrue(validator.isValid("CorrectHorseBattery12", null));
    }

    @Test
    void asciiExceeding72BytesIsInvalid() {
        assertFalse(validator.isValid("a".repeat(73), null));
        assertFalse(validator.isValid("a".repeat(100), null));
    }

    @Test
    void multibyteUtf8CharactersRespected() {
        // '€' is 3 bytes in UTF-8
        // 24 * 3 = 72 bytes -> valid
        String valid24Euro = "€".repeat(24);
        assertTrue(validator.isValid(valid24Euro, null));

        // 25 * 3 = 75 bytes -> invalid
        String invalid25Euro = "€".repeat(25);
        assertFalse(validator.isValid(invalid25Euro, null));

        // Devanagari 'ह' is 3 bytes in UTF-8
        String valid24Hindi = "ह".repeat(24);
        assertTrue(validator.isValid(valid24Hindi, null));
        String invalid25Hindi = "ह".repeat(25);
        assertFalse(validator.isValid(invalid25Hindi, null));

        // Emoji '🔒' is 4 bytes in UTF-8
        // 18 * 4 = 72 bytes -> valid
        String valid18Emoji = "🔒".repeat(18);
        assertTrue(validator.isValid(valid18Emoji, null));
        // 19 * 4 = 76 bytes -> invalid
        String invalid19Emoji = "🔒".repeat(19);
        assertFalse(validator.isValid(invalid19Emoji, null));
    }
}
