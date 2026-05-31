package com.kbmah.linkedin;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ExcelLeadRepository {
    private static final DateTimeFormatter DATE_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ExcelLeadRepository() {
    }

    static Path outputPathForToday(Path outputDir) {
        return outputDir.resolve(LocalDate.now().format(DATE_FILE_FORMAT) + ".xlsx");
    }

    static SaveResult save(List<Lead> leads, Path outputPath) throws IOException {
        try (Workbook workbook = loadOrCreateWorkbook(outputPath)) {
            Sheet sheet = workbook.getSheetAt(0);
            Set<String> existingPostEmails = readExistingPostEmails(sheet);
            Set<String> existingEmails = readExistingEmails(sheet);
            Set<String> newPostEmails = new HashSet<>();
            Set<String> newEmails = new HashSet<>();
            boolean duplicateFound = false;
            int consecutiveDuplicates = 0;
            int maxConsecutiveDuplicates = 0;
            int savedCount = 0;

            String runTimestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            for (Lead lead : leads) {
                String postEmail = postEmailKey(lead.subject(), lead.email());
                String email = emailKey(lead.email());
                if (existingPostEmails.contains(postEmail)
                        || existingEmails.contains(email)
                        || !newPostEmails.add(postEmail)
                        || !newEmails.add(email)) {
                    duplicateFound = true;
                    consecutiveDuplicates++;
                    maxConsecutiveDuplicates = Math.max(maxConsecutiveDuplicates, consecutiveDuplicates);
                    continue;
                }
                consecutiveDuplicates = 0;

                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                row.createCell(0).setCellValue(lead.subject());
                row.createCell(1).setCellValue(lead.email());
                row.createCell(2).setCellValue(EmailExtractor.extractFirstName(lead.email()));
                row.createCell(3).setCellValue(runTimestamp);
                row.createCell(4).setCellValue("N");
                savedCount++;
            }

            return writeWithFallback(workbook, outputPath, savedCount, duplicateFound, maxConsecutiveDuplicates);
        }
    }

    static String postEmailKey(String subject, String email) {
        return subjectKey(subject)
                + "\u0000"
                + emailKey(email);
    }

    private static String subjectKey(String subject) {
        return EmailExtractor.normalizeWhitespace(subject).toLowerCase(Locale.ROOT);
    }

    private static String emailKey(String email) {
        return EmailExtractor.normalizeWhitespace(email).toLowerCase(Locale.ROOT);
    }

    private static Set<String> readExistingPostEmails(Sheet sheet) {
        Set<String> postEmails = new HashSet<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Cell subjectCell = row.getCell(0);
            Cell emailCell = row.getCell(1);
            if (emailCell == null) {
                continue;
            }

            String subject = subjectCell == null ? "" : subjectCell.toString();
            String email = emailCell.toString();
            if (!emailKey(email).isEmpty()) {
                postEmails.add(postEmailKey(subject, email));
            }
        }
        return postEmails;
    }

    private static Set<String> readExistingEmails(Sheet sheet) {
        Set<String> emails = new HashSet<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Cell emailCell = row.getCell(1);
            if (emailCell == null) {
                continue;
            }

            String email = emailKey(emailCell.toString());
            if (!email.isEmpty()) {
                emails.add(email);
            }
        }
        return emails;
    }

    private static Workbook loadOrCreateWorkbook(Path outputPath) throws IOException {
        Workbook workbook;
        if (Files.exists(outputPath)) {
            try (InputStream inputStream = Files.newInputStream(outputPath)) {
                workbook = WorkbookFactory.create(inputStream);
            }
        } else {
            workbook = new XSSFWorkbook();
            workbook.createSheet("linkedin");
        }

        Sheet sheet = workbook.getSheetAt(0);
        setHeaderIfBlank(sheet, 0, "subject");
        setHeaderIfBlank(sheet, 1, "email");
        setHeaderIfBlank(sheet, 2, "firstname");
        setHeaderIfBlank(sheet, 3, "timestamp");
        setHeaderIfBlank(sheet, 4, "Is Email Sent");
        return workbook;
    }

    private static void setHeaderIfBlank(Sheet sheet, int columnIndex, String value) {
        Row header = sheet.getRow(0);
        if (header == null) {
            header = sheet.createRow(0);
        }

        Cell cell = header.getCell(columnIndex);
        if (cell == null || cell.toString().isBlank()) {
            header.createCell(columnIndex).setCellValue(value);
        }
    }

    private static SaveResult writeWithFallback(
            Workbook workbook,
            Path outputPath,
            int savedCount,
            boolean duplicateFound,
            int maxConsecutiveDuplicates
    ) throws IOException {
        try {
            writeWorkbook(workbook, outputPath);
            return new SaveResult(outputPath, savedCount, duplicateFound, maxConsecutiveDuplicates);
        } catch (IOException exception) {
            Path fallbackPath = outputPath.resolveSibling(
                    outputPath.getFileName().toString().replaceFirst("(\\.[^.]+)?$", ".latest$1")
            );
            writeWorkbook(workbook, fallbackPath);
            return new SaveResult(fallbackPath, savedCount, duplicateFound, maxConsecutiveDuplicates);
        }
    }

    private static void writeWorkbook(Workbook workbook, Path path) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            workbook.write(outputStream);
        }
    }
}
