package com.sec.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import org.apache.hadoop.fs.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroReadSupport;
import org.apache.avro.Schema;
import org.apache.parquet.hadoop.ParquetReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;

@Component
public class ParquetFilingReader {
    private static final Logger log = LoggerFactory.getLogger(ParquetFilingReader.class);

    private static final Pattern YEAR_IN_FILENAME = Pattern.compile("(20\\d{2})");

    private final SchemaResolver schemaResolver;

    public ParquetFilingReader(SchemaResolver schemaResolver) {
        this.schemaResolver = schemaResolver;
    }

    public List<FilingRecord> read(java.nio.file.Path file) throws IOException {
        log.info("Path of Parquet File: {}", file.getFileName());

        if (!Files.exists(file)) {
            return List.of();
        }

        List<FilingRecord> records = new ArrayList<>();
        Configuration configuration = new Configuration();

        // Load Avro schema from resources
        Schema schema;
        try (InputStream inputStream = new ClassPathResource("filling_record.avsc").getInputStream()) {
            schema = new Schema.Parser().parse(inputStream);
        }
        AvroReadSupport.setAvroReadSchema(configuration, schema);

        Path parquetPath = new Path(file.toUri());

        try (ParquetReader<GenericRecord> reader = ParquetReader
                .builder(new AvroReadSupport<GenericRecord>(), parquetPath).withConf(configuration).build()) {

            GenericRecord row;
            SchemaResolver.ResolvedSchema resolvedSchema = null;

            while ((row = reader.read()) != null) {
                if (resolvedSchema == null) {
                    resolvedSchema = schemaResolver.resolve(row.getSchema());
                }

                records.addAll(mapRows(row, resolvedSchema, file.getFileName().toString()));
            }
        }

        log.info("Size of records: {}", records.size());
        return records;
    }

    private List<FilingRecord> mapRows(GenericRecord row, SchemaResolver.ResolvedSchema schema, String sourceFile) {
        List<FilingRecord> records = new ArrayList<>();

        if (schema.section() != null && schema.text() != null) {
            FilingRecord record = singleSectionRecord(row, schema, sourceFile);
            if (record != null && isTargetSection(record.section()) && isTargetFormType(record.formType())) {
                records.add(record);
            }
            return records;
        }

        addWideItemRecord(records, row, schema, sourceFile, "item7", "7", "Management's Discussion and Analysis");
        addWideItemRecord(records, row, schema, sourceFile, "item7a", "7A",
                "Quantitative and Qualitative Disclosures About Market Risk");
        return records;
    }

    private FilingRecord singleSectionRecord(GenericRecord row, SchemaResolver.ResolvedSchema schema,
            String sourceFile) {
        String section = stringValue(row, schema.section());
        String text = stringValue(row, schema.text());
        if (section == null || text == null || text.isBlank()) {
            return null;
        }
        return buildRecord(row, schema, sourceFile, section.trim(), stringValue(row, schema.sectionTitle()),
                text.trim());
    }

    private void addWideItemRecord(List<FilingRecord> records, GenericRecord row, SchemaResolver.ResolvedSchema schema,
            String sourceFile, String normalizedItemName, String section, String sectionTitle) {
        String itemColumn = schema.itemColumn(normalizedItemName);
        String text = stringValue(row, itemColumn);
        if (text == null || text.isBlank()) {
            return;
        }

        FilingRecord record = buildRecord(row, schema, sourceFile, section, sectionTitle, text.trim());
        if (isTargetFormType(record.formType())) {
            records.add(record);
        }
    }

    private FilingRecord buildRecord(GenericRecord row, SchemaResolver.ResolvedSchema schema, String sourceFile,
            String section, String sectionTitle, String text) {
        Integer year = resolveYear(row, schema, sourceFile);
        String filingDate = resolveDate(row, schema);
        return new FilingRecord(stringValue(row, schema.company()), stringValue(row, schema.cik()), year, filingDate,
                section, sectionTitle, text, stringValue(row, schema.formType()), sourceFile);
    }

    private boolean isTargetSection(String section) {
        String normalized = section == null ? "" : section.trim().toUpperCase();
        return normalized.equals("7") || normalized.equals("7A") || normalized.equals("ITEM 7")
                || normalized.equals("ITEM 7A");
    }

    private boolean isTargetFormType(String formType) {
        if (formType == null || formType.isBlank()) {
            return true;
        }
        return "10-K".equalsIgnoreCase(formType.trim());
    }

    private String stringValue(GenericRecord record, String field) {
        if (field == null || record.get(field) == null) {
            return null;
        }
        return String.valueOf(record.get(field));
    }

    private Integer integerValue(GenericRecord record, String field) {
        String value = stringValue(record, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer resolveYear(GenericRecord record, SchemaResolver.ResolvedSchema schema, String sourceFile) {
        Integer explicitYear = integerValue(record, schema.year());
        if (explicitYear != null) {
            return explicitYear;
        }

        Object dateValue = schema.filingDate() == null ? null : record.get(schema.filingDate());
        if (dateValue instanceof LocalDate localDate) {
            return localDate.getYear();
        }
        if (dateValue instanceof LocalDateTime localDateTime) {
            return localDateTime.getYear();
        }
        if (dateValue instanceof Number number) {
            return LocalDateTime.ofEpochSecond(number.longValue() / 1_000_000L, 0, ZoneOffset.UTC).getYear();
        }

        Matcher matcher = YEAR_IN_FILENAME.matcher(sourceFile);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String resolveDate(GenericRecord record, SchemaResolver.ResolvedSchema schema) {
        if (schema.filingDate() == null) {
            return null;
        }
        Object value = record.get(schema.filingDate());
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate().toString();
        }
        return String.valueOf(value);
    }
}
