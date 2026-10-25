package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Une cle oubliee dans une langue s'afficherait en cle brute, et seulement en jeu. */
class MessagesTest {

    private static Set<String> keys(String language) throws Exception {
        try (InputStream in = MessagesTest.class.getClassLoader()
                .getResourceAsStream(ThimblePlugin.messagesFile(language))) {
            assertNotNull(in, "fichier de messages absent du jar pour " + language);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getKeys(true);
        }
    }

    @Test
    void toutesLesLanguesOntLesMemesCles() throws Exception {
        Set<String> reference = keys("fr");
        for (String language : ThimblePlugin.LANGUAGES) {
            assertEquals(reference, keys(language), "cles differentes entre fr et " + language);
        }
    }
}
