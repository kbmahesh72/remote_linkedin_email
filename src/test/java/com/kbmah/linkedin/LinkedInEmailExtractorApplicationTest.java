package com.kbmah.linkedin;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedInEmailExtractorApplicationTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsToExistingPlaywrightProfile() {
        AppConfig config = AppConfig.parse(new String[0]);

        assertEquals(Path.of("playwright-profile"), config.profileDir());
        assertEquals(Path.of("automation-output"), config.outputDir());
        assertEquals(7.0, config.intervalMinutes());
        assertEquals(List.of("Hiring Java C2C Remote", "Hiring Java W2 Remote"), config.queries());
        assertEquals("Hiring Java C2C Remote", config.query());
    }

    @Test
    void readsIntervalFromConfigFile() throws Exception {
        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, """
                queries=Config query
                interval-minutes=7
                max-emails=12
                """);

        AppConfig config = AppConfig.parse(new String[]{"--config", configPath.toString()});

        assertEquals("Config query", config.query());
        assertEquals(List.of("Config query"), config.queries());
        assertEquals(7.0, config.intervalMinutes());
        assertEquals(12, config.maxEmails());
    }

    @Test
    void readsSubjectVariantsPathFromConfigFile() throws Exception {
        Path variantsPath = tempDir.resolve("subject-variants.txt");
        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, """
                subject.variants.path=%s
                """.formatted(escapePropertiesPath(variantsPath)));

        AppConfig config = AppConfig.parse(new String[]{"--config", configPath.toString()});

        assertEquals(variantsPath, config.emailConfig().subjectVariantsPath());
    }

    @Test
    void readsMultipleSenderAccountsFromConfigFile() throws Exception {
        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, """
                email.accounts.file=
                email.accounts=1,2
                email.account.1.username=first@example.com
                email.account.1.app.password=first-password
                email.account.1.cc.email=first-cc@example.com
                email.account.1.attachment.path=resume-one.docx
                email.account.2.username=second@example.com
                email.account.2.app.password=second-password
                email.account.2.cc.email=second-cc@example.com
                email.account.2.attachment.path=resume-two.docx
                """);

        AppConfig config = AppConfig.parse(new String[]{"--config", configPath.toString()});

        assertEquals(2, config.emailConfig().accounts().size());
        assertEquals("1", config.emailConfig().accounts().get(0).id());
        assertEquals("first@example.com", config.emailConfig().accounts().get(0).gmailUsername());
        assertEquals("first-cc@example.com", config.emailConfig().accounts().get(0).ccEmail());
        assertEquals("2", config.emailConfig().accounts().get(1).id());
        assertEquals("second@example.com", config.emailConfig().accounts().get(1).gmailUsername());
        assertEquals("second-cc@example.com", config.emailConfig().accounts().get(1).ccEmail());
        assertEquals(Path.of("resume-two.docx"), config.emailConfig().accounts().get(1).attachmentPath());
    }

    @Test
    void readsLargeSenderAccountPoolFromCsvFile() throws Exception {
        Path accountsPath = tempDir.resolve("email-accounts.csv");
        StringBuilder accounts = new StringBuilder(
                "id,username,appPassword,ccEmail,attachmentPath,subjectPrefix,subjectPath,subjectVariantsPath,bodyPath\n"
        );
        for (int idx = 1; idx <= 50; idx++) {
            accounts.append("%d,sender%02d@example.com,password-%02d,cc%02d@example.com,resume/sender%02d.docx,,sender%02d@example.com.txt,sender%02d@example.com.variants.txt,sender%02d@example.com.txt\n"
                    .formatted(idx, idx, idx, idx, idx, idx, idx, idx));
        }
        Files.writeString(accountsPath, accounts);

        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, """
                email.accounts.file=%s
                """.formatted(escapePropertiesPath(accountsPath)));

        AppConfig config = AppConfig.parse(new String[]{"--config", configPath.toString()});

        assertEquals(50, config.emailConfig().accounts().size());
        assertEquals("1", config.emailConfig().accounts().get(0).id());
        assertEquals("sender01@example.com", config.emailConfig().accounts().get(0).gmailUsername());
        assertEquals("cc01@example.com", config.emailConfig().accounts().get(0).ccEmail());
        assertEquals(Path.of("resume/sender01.docx"), config.emailConfig().accounts().get(0).attachmentPath());
        assertEquals(Path.of("templates", "subjects", "sender01@example.com.txt"),
                config.emailConfig().accounts().get(0).subjectTemplatePath());
        assertEquals(Path.of("templates", "subjects", "sender01@example.com.variants.txt"),
                config.emailConfig().accounts().get(0).subjectVariantsPath());
        assertEquals(Path.of("templates", "body", "sender01@example.com.txt"),
                config.emailConfig().accounts().get(0).bodyTemplatePath());
        assertEquals("50", config.emailConfig().accounts().get(49).id());
        assertEquals("sender50@example.com", config.emailConfig().accounts().get(49).gmailUsername());
        assertEquals("cc50@example.com", config.emailConfig().accounts().get(49).ccEmail());
        assertEquals(Path.of("resume/sender50.docx"), config.emailConfig().accounts().get(49).attachmentPath());
    }

    @Test
    void defaultEmailAssetsAreInsideCurrentProjectFolder() {
        AppConfig config = AppConfig.parse(new String[0]);

        assertEquals(Path.of("templates/subjects/default.txt"), config.emailConfig().subjectTemplatePath());
        assertNull(config.emailConfig().subjectVariantsPath());
        assertEquals(Path.of("templates/body/default.txt"), config.emailConfig().bodyTemplatePath());
        assertNull(config.emailConfig().attachmentPath());
    }

    @Test
    void bodyFirstnameIsRendered() {
        assertEquals(
                "Dear John,\nBody\nRegards,\nSender One",
                EmailSender.renderBody("Dear ${firstname},\nBody\nRegards,\nSender One", "John")
        );
    }

    @Test
    void subjectPrefixIsPrependedBeforeRenderedSubject() {
        AppConfig.EmailAccount suryaAccount = new AppConfig.EmailAccount(
                "2",
                "surya.p2805@gmail.com",
                "",
                "",
                Path.of("resume/Surya_Narayana_Java.pdf"),
                "GC - ",
                Path.of("templates/subjects/surya.p2805@gmail.com.txt"),
                null,
                Path.of("templates/body/surya.p2805@gmail.com.txt")
        );

        assertEquals("GC - Hello Surya", EmailSender.renderSubject(suryaAccount, "Hello ${firstname}", "Surya"));
    }

    @Test
    void commandLineOverridesConfigFile() throws Exception {
        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, "interval-minutes=7%n".formatted());

        AppConfig config = AppConfig.parse(new String[]{
                "--config", configPath.toString(),
                "--interval-minutes", "3"
        });

        assertEquals(3.0, config.intervalMinutes());
    }

    @Test
    void manualEmailSenderSkipsMissingTodaysWorkbook() throws Exception {
        Path configPath = tempDir.resolve("application.properties");
        writeConfig(configPath, """
                output-dir=%s
                email.enabled=false
                run-once=true
                """.formatted(tempDir.resolve("missing-output")));

        AppConfig config = AppConfig.parse(new String[]{"--config", configPath.toString()});

        assertEquals(0, PendingEmailSenderApplication.run(config));
    }

    @Test
    void extractsFirstNameWithoutNumbers() {
        assertEquals("John", EmailExtractor.extractFirstName("john123@example.com"));
        assertEquals("John", EmailExtractor.extractFirstName("123john@example.com"));
        assertEquals("John", EmailExtractor.extractFirstName("john.42@example.com"));
        assertEquals("", EmailExtractor.extractFirstName("123@example.com"));
        assertEquals("Mary", EmailExtractor.extractFirstName("mary-jane99@example.com"));
    }

    @Test
    void extractsUniqueEmailIdsFromPostText() {
        String postText = """
                Feed post Hiring Java developers for c2c roles.
                Contact John123@Example.com or recruiter@test.org.
                Duplicate JOHN123@example.com should not create another row.
                Like Comment Repost Send
                """;

        List<Lead> leads = EmailExtractor.extractLeads(postText, "Java c2c hiring");

        assertEquals(2, leads.size());
        assertEquals("john123@example.com", leads.get(0).email());
        assertEquals("recruiter@test.org", leads.get(1).email());
        assertTrue(leads.get(0).subject().startsWith("Java c2c hiring"));
    }

    @Test
    void recognizesLinkedInMoreButtonLabelVariants() {
        for (String label : List.of(
                "more", "..more", ".. more", "more..", "more ..",
                "...more", "... more", "more...", "more ...",
                "…more", "… more", "more…", "more …"
        )) {
            assertTrue(EmailExtractor.isPostExpandLabel(label), label);
        }

        assertTrue(EmailExtractor.isPostExpandLabel("See more"));
        assertTrue(EmailExtractor.isPostExpandLabel("Show more"));
        assertFalse(EmailExtractor.isPostExpandLabel("Comment"));
        assertFalse(EmailExtractor.isPostExpandLabel("Send"));
    }

    @Test
    void skipsPostsWithBlockedKeywords() {
        assertTrue(EmailExtractor.extractLeads(
                "Feed post Sales role available. Contact sales@example.com",
                "Java c2c hiring"
        ).isEmpty());
        assertTrue(EmailExtractor.extractLeads(
                "Feed post Bench candidates available. Contact bench@example.com",
                "Java c2c hiring"
        ).isEmpty());
    }

    @Test
    void savesExcelWithHeadersFirstnameAndDedupedPostsAndEmails() throws Exception {
        Path output = tempDir.resolve("linkedin.com.xlsx");
        List<Lead> leads = List.of(
                new Lead("john123@example.com", "Java c2c hiring - first"),
                new Lead("john123@example.com", "Java c2c hiring - first"),
                new Lead("JOHN123@example.com", "Java c2c hiring - different post"),
                new Lead("mary-jane99@example.com", "Java c2c hiring - second")
        );

        SaveResult firstSave = ExcelLeadRepository.save(leads, output);
        SaveResult secondSave = ExcelLeadRepository.save(leads, output);

        assertEquals(2, firstSave.savedCount());
        assertTrue(firstSave.duplicateFound());
        assertEquals(2, firstSave.maxConsecutiveDuplicates());
        assertEquals(0, secondSave.savedCount());
        assertTrue(secondSave.duplicateFound());
        assertEquals(4, secondSave.maxConsecutiveDuplicates());

        try (InputStream inputStream = Files.newInputStream(output);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("subject", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("email", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("firstname", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("timestamp", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("Is Email Sent", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("john123@example.com", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("John", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("N", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("mary-jane99@example.com", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("Mary", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("N", sheet.getRow(2).getCell(4).getStringCellValue());
        }
    }

    @Test
    void doesNotOverwriteExistingEmailSentValues() throws Exception {
        Path output = tempDir.resolve("existing.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("linkedin");
            sheet.createRow(0);
            sheet.getRow(0).createCell(0).setCellValue("subject");
            sheet.getRow(0).createCell(1).setCellValue("email");
            sheet.getRow(0).createCell(2).setCellValue("firstname");
            sheet.getRow(0).createCell(3).setCellValue("timestamp");
            sheet.getRow(0).createCell(4).setCellValue("Is Email Sent");
            sheet.createRow(1);
            sheet.getRow(1).createCell(0).setCellValue("Existing subject");
            sheet.getRow(1).createCell(1).setCellValue("already@example.com");
            sheet.getRow(1).createCell(2).setCellValue("Already");
            sheet.getRow(1).createCell(3).setCellValue("2026-05-21 00:00:00");
            sheet.getRow(1).createCell(4).setCellValue("Y");
            try (OutputStream outputStream = Files.newOutputStream(output)) {
                workbook.write(outputStream);
            }
        }

        ExcelLeadRepository.save(List.of(
                new Lead("already@example.com", "Duplicate subject"),
                new Lead("new@example.com", "New subject")
        ), output);

        try (InputStream inputStream = Files.newInputStream(output);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Y", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("new@example.com", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("N", sheet.getRow(2).getCell(4).getStringCellValue());
        }
    }

    @Test
    void emailSenderMarksOnlyPendingRowsAfterSuccessfulDryRun() throws Exception {
        Path output = tempDir.resolve("emails.xlsx");
        Path subject = tempDir.resolve("subject.txt");
        Path body = tempDir.resolve("padmaja_body.txt");
        Path resume = tempDir.resolve("resume.docx");
        Files.writeString(subject, "Test subject");
        Files.writeString(body, "Dear ${firstname},\nBody");
        Files.writeString(resume, "resume");

        ExcelLeadRepository.save(List.of(
                new Lead("pending@example.com", "Pending subject"),
                new Lead("sent@example.com", "Sent subject")
        ), output);

        Workbook existingWorkbook;
        try (InputStream inputStream = Files.newInputStream(output)) {
            existingWorkbook = WorkbookFactory.create(inputStream);
        }
        try (Workbook workbook = existingWorkbook) {
            Sheet sheet = workbook.getSheetAt(0);
            sheet.getRow(2).getCell(4).setCellValue("Y");
            try (OutputStream outputStream = Files.newOutputStream(output)) {
                workbook.write(outputStream);
            }
        }

        int sentCount = EmailSender.sendPendingEmails(output, new AppConfig.EmailConfig(
                true,
                true,
                "sender@example.com",
                "",
                "cc@example.com",
                subject,
                null,
                body,
                resume,
                smtpConfig()
        ));

        assertEquals(1, sentCount);
        try (InputStream inputStream = Files.newInputStream(output);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Y", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("Y", sheet.getRow(2).getCell(4).getStringCellValue());
        }
    }

    @Test
    void emailSenderAllowsBlankCcEmailForAccount() throws Exception {
        Path output = tempDir.resolve("emails-no-cc.xlsx");
        Path subject = tempDir.resolve("subject-no-cc.txt");
        Path body = tempDir.resolve("body-no-cc.txt");
        Path resume = tempDir.resolve("resume-no-cc.docx");
        Files.writeString(subject, "Test subject");
        Files.writeString(body, "Dear ${firstname},\nBody");
        Files.writeString(resume, "resume");

        ExcelLeadRepository.save(List.of(new Lead("pending@example.com", "Pending subject")), output);

        int sentCount = EmailSender.sendPendingEmails(output, new AppConfig.EmailConfig(
                true,
                true,
                "sender@example.com",
                "",
                "",
                subject,
                null,
                body,
                resume,
                smtpConfig()
        ));

        assertEquals(1, sentCount);
    }

    @Test
    void emailSenderSendsEachPendingRowFromEveryConfiguredAccount() throws Exception {
        Path output = tempDir.resolve("emails-all-accounts.xlsx");
        Path subject = tempDir.resolve("subject-all-accounts.txt");
        Path body = tempDir.resolve("body-all-accounts.txt");
        Path firstResume = tempDir.resolve("resume-one.docx");
        Path secondResume = tempDir.resolve("resume-two.docx");
        Files.writeString(subject, "Test subject");
        Files.writeString(body, "Dear ${firstname},\nRegards");
        Files.writeString(firstResume, "resume one");
        Files.writeString(secondResume, "resume two");

        ExcelLeadRepository.save(List.of(new Lead("pending@example.com", "Pending subject")), output);

        int sentCount = EmailSender.sendPendingEmails(output, new AppConfig.EmailConfig(
                true,
                true,
                "",
                "",
                "",
                subject,
                null,
                body,
                null,
                List.of(
                        new AppConfig.EmailAccount("1", "first@example.com", "", "", firstResume,
                                "", subject, null, body),
                        new AppConfig.EmailAccount("2", "second@example.com", "", "", secondResume,
                                "", subject, null, body)
                ),
                smtpConfig()
        ));

        assertEquals(2, sentCount);
        try (InputStream inputStream = Files.newInputStream(output);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            assertEquals("Y", workbook.getSheetAt(0).getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void dateWorkbookUsesSystemDateFileName() {
        Path output = ExcelLeadRepository.outputPathForToday(tempDir);
        String expectedName = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";

        assertEquals(tempDir.resolve(expectedName), output);
    }

    @Test
    void outputFileArgumentKeepsDateBasedFileNameAndUsesParentDirectory() {
        AppConfig config = AppConfig.parse(
                new String[]{
                        "--output", "reports\\ignored.xlsx",
                        "--run-once",
                        "--interval-minutes", "0.1"
                }
        );

        assertEquals(Path.of("reports"), config.outputDir());
        assertTrue(config.runOnce());
        assertEquals(0.1, config.intervalMinutes());
    }

    private static String escapePropertiesPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void writeConfig(Path configPath, String overrides) throws Exception {
        String baseConfig = Files.readString(Path.of("application.properties"));
        Files.writeString(configPath, baseConfig + System.lineSeparator() + overrides);
    }

    private static AppConfig.SmtpConfig smtpConfig() {
        return new AppConfig.SmtpConfig(true, true, "smtp.gmail.com", 587);
    }
}
