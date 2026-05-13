package com.sec.rag.ingestion;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.springframework.stereotype.Component;

@Component
public class SchemaResolver {

    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "company", List.of("companyname", "company", "issuername", "registrantname"),
            "cik", List.of("cik", "centralindexkey"),
            "filingDate", List.of("filingdate", "datefiled", "fileddate", "date"),
            "year", List.of("filingyear", "year", "reportyear"),
            "section", List.of("section", "item", "sectionid", "itemnumber"),
            "sectionTitle", List.of("sectiontitle", "title", "itemtitle"),
            "text", List.of("text", "content", "body", "sectiontext"),
            "formType", List.of("formtype", "form", "documenttype")
    );

    public ResolvedSchema resolve(Schema schema) {
        Set<String> fieldNames = schema.getFields().stream()
                .map(Schema.Field::name)
                .collect(Collectors.toSet());

        Map<String, String> normalizedLookup = fieldNames.stream()
                .collect(Collectors.toMap(this::normalize, Function.identity(), (left, right) -> left));

        Map<String, String> itemColumns = new HashMap<>();
        normalizedLookup.forEach((normalized, original) -> {
            if (normalized.matches("item[0-9]{1,2}[a-z]?")) {
                itemColumns.put(normalized, original);
            }
        });

        return new ResolvedSchema(
                lookup("company", normalizedLookup),
                lookup("cik", normalizedLookup),
                lookup("filingDate", normalizedLookup),
                lookup("year", normalizedLookup),
                lookup("section", normalizedLookup),
                lookup("sectionTitle", normalizedLookup),
                lookup("text", normalizedLookup),
                lookup("formType", normalizedLookup),
                itemColumns
        );
    }

    private String lookup(String logicalName, Map<String, String> normalizedLookup) {
        return CANDIDATES.getOrDefault(logicalName, List.of()).stream()
                .filter(normalizedLookup::containsKey)
                .map(normalizedLookup::get)
                .findFirst()
                .orElse(null);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public record ResolvedSchema(
            String company,
            String cik,
            String filingDate,
            String year,
            String section,
            String sectionTitle,
            String text,
            String formType,
            Map<String, String> itemColumns
    ) {
        public String itemColumn(String normalizedItemName) {
            return itemColumns.get(normalizedItemName);
        }
    }
}
