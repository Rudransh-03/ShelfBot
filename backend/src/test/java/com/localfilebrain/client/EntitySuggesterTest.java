package com.localfilebrain.client;

import com.localfilebrain.client.EntitySuggester.Suggestion;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;
import com.localfilebrain.ingestion.IndexMetadataStore.EntityRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EntitySuggesterTest {

    private static EntityRow e(String path, String name, String gstin, String pan) {
        return new EntityRow(path, name, gstin, pan);
    }

    private static Client client(String id, String name, String... tokens) {
        List<String> norms = new ArrayList<>();
        for (String t : tokens) norms.add(IndexMetadataStore.normToken(t));
        return new Client(id, name, List.of(tokens), norms);
    }

    @Test
    void groupsSameGstinAcrossNameVariants() {
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", "Acme Corp",        "29ABCDE1234F1Z5", null),
                e("/b.pdf", "ACME CORPORATION", "29ABCDE1234F1Z5", null),
                e("/c.pdf", "Acme Corp",        "29ABCDE1234F1Z5", null)), List.of(), Set.of());
        assertEquals(1, s.size());
        assertEquals(3, s.get(0).fileCount());
        assertEquals("Acme Corp", s.get(0).name()); // most common variant
        assertEquals("29ABCDE1234F1Z5", s.get(0).gstin());
    }

    @Test
    void groupsByNameWhenNoIds() {
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", "Verma Textiles", null, null),
                e("/b.pdf", "Verma Textiles", null, null)), List.of(), Set.of());
        assertEquals(1, s.size());
        assertEquals(2, s.get(0).fileCount());
        assertTrue(s.get(0).key().startsWith("name:"));
    }

    @Test
    void excludesAlreadyRegisteredClients() {
        // A client already registered with that GSTIN must not be re-suggested.
        Client acme = client("A", "Acme Corp", "Acme Corp", "29ABCDE1234F1Z5");
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", "Acme Corp", "29ABCDE1234F1Z5", null)), List.of(acme), Set.of());
        assertTrue(s.isEmpty());
    }

    @Test
    void excludesDismissedSuggestions() {
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", "Globex", "27ZZZZZ9999Z1Z2", null)), List.of(),
                Set.of("gstin:27ZZZZZ9999Z1Z2"));
        assertTrue(s.isEmpty());
    }

    @Test
    void sortsByFileCountDescending() {
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", "Small Co", null, null),
                e("/b.pdf", "Big Co",   null, null),
                e("/c.pdf", "Big Co",   null, null)), List.of(), Set.of());
        assertEquals("Big Co", s.get(0).name());
        assertEquals(2, s.get(0).fileCount());
    }

    @Test
    void ignoresRowsWithNoUsableIdentity() {
        List<Suggestion> s = EntitySuggester.suggest(List.of(
                e("/a.pdf", null, null, null)), List.of(), Set.of());
        assertTrue(s.isEmpty());
    }
}
