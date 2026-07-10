package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PersonaControllerTest {

    @Test
    void detectsSupportedImageSignatures() {
        assertEquals("image/png", PersonaController.detectImageMime(new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0
        }));
        assertEquals("image/jpeg", PersonaController.detectImageMime(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0
        }));
        assertEquals("image/webp", PersonaController.detectImageMime(new byte[] {
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
        }));
    }

    @Test
    void rejectsUnknownOrTruncatedImages() {
        assertNull(PersonaController.detectImageMime(new byte[] {'<', 's', 'v', 'g'}));
        assertNull(PersonaController.detectImageMime(new byte[12]));
    }
}
