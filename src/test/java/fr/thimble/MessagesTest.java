package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** A key forgotten in one language would show as a raw key, and only in game. */
class MessagesTest {

    private static Set<String> keys(String language) throws Exception {
        try (InputStream in = MessagesTest.class.getClassLoader()
                .getResourceAsStream(ThimblePlugin.messagesFile(language))) {
            assertNotNull(in, "messages file missing from the jar for " + language);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getKeys(true);
        }
    }

    @Test
    void allLanguagesHaveTheSameKeys() throws Exception {
        Set<String> reference = keys("fr");
        for (String language : ThimblePlugin.LANGUAGES) {
            assertEquals(reference, keys(language), "keys differ between fr and " + language);
        }
    }
}
