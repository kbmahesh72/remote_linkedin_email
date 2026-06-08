package com.kbmah.linkedin;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

final class EmailSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSender.class);
    private static final int EMAIL_COLUMN = 1;
    private static final int FIRST_NAME_COLUMN = 2;
    private static final int IS_EMAIL_SENT_COLUMN = 4;

    private EmailSender() {
    }

    static int sendPendingEmails(Path workbookPath, AppConfig.EmailConfig config) throws IOException, MessagingException {
        return sendPendingEmails(workbookPath, config, Duration.ZERO);
    }

    static int sendPendingEmails(
            Path workbookPath,
            AppConfig.EmailConfig config,
            Duration delayBeforeEachEmail
    ) throws IOException, MessagingException {
        if (!config.enabled()) {
            LOGGER.info("Email sending is disabled.");
            return 0;
        }
        validateConfig(config);

        Map<String, Session> sessionsByAccountId = new HashMap<>();
        Map<String, List<String>> subjectsByAccountId = new HashMap<>();
        Map<String, String> bodyByAccountId = new HashMap<>();
        int sentCount = 0;

        Workbook workbook;
        try (InputStream inputStream = Files.newInputStream(workbookPath)) {
            workbook = WorkbookFactory.create(inputStream);
        }

        try (workbook) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isMarkedSent(row)) {
                    continue;
                }

                String toEmail = cellText(row, EMAIL_COLUMN);
                if (toEmail.isBlank()) {
                    continue;
                }

                String firstName = cellText(row, FIRST_NAME_COLUMN);
                for (AppConfig.EmailAccount account : config.accounts()) {
                    List<String> subjects = subjectsByAccountId.computeIfAbsent(account.id(), ignored -> loadSubjects(config, account));
                    String bodyTemplate = bodyByAccountId.computeIfAbsent(account.id(), ignored -> loadBodyTemplate(config, account));
                    String subject = renderSubject(account, chooseSubject(subjects), firstName);
                    String body = renderBody(bodyTemplate, firstName);
                    waitBeforeSending(delayBeforeEachEmail, toEmail);
                    Session session = sessionsByAccountId.computeIfAbsent(
                            account.id(),
                            ignored -> createSession(account, config.smtpConfig())
                    );
                    sendOne(session, config, account, toEmail, subject, body);
                    sentCount++;
                    LOGGER.info("Email sent to {} from {}", toEmail, account.gmailUsername());
                }
                markSent(row);
            }

            if (sentCount > 0) {
                writeWorkbook(workbook, workbookPath);
            }
        }

        LOGGER.info("Email sender processed {} pending rows.", sentCount);
        return sentCount;
    }

    static String renderBody(String bodyTemplate, String firstName) {
        return bodyTemplate.replace("${firstname}", firstName);
    }

    static String renderSubject(AppConfig.EmailAccount account, String subjectTemplate, String firstName) {
        return account.subjectPrefix() + subjectTemplate.replace("${firstname}", firstName);
    }

    private static String loadBodyTemplate(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        try {
            return Files.readString(bodyTemplatePathFor(config, account), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read body template for account '" + account.id() + "'.", exception);
        }
    }

    private static List<String> loadSubjects(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        try {
            return readSubjects(config, account);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read subject template for account '" + account.id() + "'.", exception);
        }
    }

    private static List<String> readSubjects(AppConfig.EmailConfig config, AppConfig.EmailAccount account) throws IOException {
        Path variantsPath = subjectVariantsPathFor(config, account);
        if (variantsPath != null) {
            List<String> variants = Files.readAllLines(variantsPath, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(subject -> !subject.isBlank() && !subject.startsWith("#"))
                    .toList();
            if (!variants.isEmpty()) {
                return variants;
            }
            LOGGER.warn("Subject variants file is empty. Falling back to subject template: {}",
                    subjectTemplatePathFor(config, account));
        }

        return List.of(Files.readString(subjectTemplatePathFor(config, account), StandardCharsets.UTF_8).trim());
    }

    private static String chooseSubject(List<String> subjects) {
        if (subjects.size() == 1) {
            return subjects.get(0);
        }
        return subjects.get(ThreadLocalRandom.current().nextInt(subjects.size()));
    }

    private static void waitBeforeSending(Duration delay, String toEmail) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }

        LOGGER.info("Waiting {} seconds before sending email to {}.", delay.toSeconds(), toEmail);
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to send email to " + toEmail, exception);
        }
    }

    private static void writeWorkbook(Workbook workbook, Path workbookPath) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(workbookPath)) {
            workbook.write(outputStream);
        }
    }

    private static void validateConfig(AppConfig.EmailConfig config) {
        if (config.accounts().isEmpty()) {
            throw new IllegalStateException("At least one email account is required when email.enabled=true.");
        }
        for (AppConfig.EmailAccount account : config.accounts()) {
            validateAccount(config, account);
        }
    }

    private static void validateAccount(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        if (account.gmailUsername().isBlank()) {
            throw new IllegalStateException("email account '" + account.id() + "' username is required when email.enabled=true.");
        }
        if (!config.dryRun() && account.gmailAppPassword().isBlank()) {
            throw new IllegalStateException("email account '" + account.id()
                    + "' app password is required when email.dry-run=false.");
        }
        Path subjectTemplatePath = subjectTemplatePathFor(config, account);
        if (!Files.isRegularFile(subjectTemplatePath)) {
            throw new IllegalStateException("Subject template not found for account '" + account.id() + "': "
                    + subjectTemplatePath);
        }
        Path subjectVariantsPath = subjectVariantsPathFor(config, account);
        if (subjectVariantsPath != null && !Files.isRegularFile(subjectVariantsPath)) {
            throw new IllegalStateException("Subject variants file not found for account '" + account.id() + "': "
                    + subjectVariantsPath);
        }
        Path bodyTemplatePath = bodyTemplatePathFor(config, account);
        if (!Files.isRegularFile(bodyTemplatePath)) {
            throw new IllegalStateException("Body template not found for account '" + account.id() + "': "
                    + bodyTemplatePath);
        }
        Path attachmentPath = attachmentPathFor(config, account);
        if (attachmentPath == null) {
            throw new IllegalStateException("Resume attachment path is required for email account '" + account.id()
                    + "'. Set attachmentPath in the CSV row.");
        }
        if (!Files.isRegularFile(attachmentPath)) {
            throw new IllegalStateException("Resume attachment not found for account '" + account.id() + "': "
                    + attachmentPath);
        }
    }

    private static Path attachmentPathFor(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        return account.hasAttachmentPath() ? account.attachmentPath() : config.attachmentPath();
    }

    private static String ccEmailFor(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        return account.ccEmail().isBlank() ? config.ccEmail() : account.ccEmail();
    }

    private static Path subjectTemplatePathFor(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        return account.subjectTemplatePath() == null ? config.subjectTemplatePath() : account.subjectTemplatePath();
    }

    private static Path subjectVariantsPathFor(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        return account.subjectVariantsPath() == null ? config.subjectVariantsPath() : account.subjectVariantsPath();
    }

    private static Path bodyTemplatePathFor(AppConfig.EmailConfig config, AppConfig.EmailAccount account) {
        return account.bodyTemplatePath() == null ? config.bodyTemplatePath() : account.bodyTemplatePath();
    }

    private static Session createSession(AppConfig.EmailAccount account, AppConfig.SmtpConfig smtpConfig) {
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", Boolean.toString(smtpConfig.auth()));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(smtpConfig.startTls()));
        properties.put("mail.smtp.host", smtpConfig.host());
        properties.put("mail.smtp.port", Integer.toString(smtpConfig.port()));

        return Session.getInstance(properties, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.gmailUsername(), account.gmailAppPassword());
            }
        });
    }

    private static void sendOne(
            Session session,
            AppConfig.EmailConfig config,
            AppConfig.EmailAccount account,
            String toEmail,
            String subject,
            String body
    ) throws MessagingException {
        if (config.dryRun()) {
            String ccEmail = ccEmailFor(config, account);
            if (ccEmail.isBlank()) {
                LOGGER.info("Dry run enabled. Would send email to {} from {} without cc.",
                        toEmail, account.gmailUsername());
            } else {
                LOGGER.info("Dry run enabled. Would send email to {} from {} with cc {}.",
                        toEmail, account.gmailUsername(), ccEmail);
            }
            return;
        }

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(account.gmailUsername()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        String ccEmail = ccEmailFor(config, account);
        if (!ccEmail.isBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccEmail));
        }
        message.setSubject(subject);
        message.setContent(contentWithAttachment(body, attachmentPathFor(config, account)));
        try {
            Transport.send(message);
        } catch (AuthenticationFailedException exception) {
            throw new GmailAuthenticationFailureException("Gmail authentication/authorization failed.", exception);
        } catch (MessagingException exception) {
            if (isGmailAuthenticationFailure(exception)) {
                throw new GmailAuthenticationFailureException("Gmail authentication/authorization failed.", exception);
            }
            throw exception;
        }
    }

    private static boolean isGmailAuthenticationFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AuthenticationFailedException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("auth")
                        || lowerMessage.contains("authorization")
                        || lowerMessage.contains("credential")
                        || lowerMessage.contains("username and password")
                        || lowerMessage.contains("535")
                        || lowerMessage.contains("5.7.8")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static Multipart contentWithAttachment(String body, Path attachmentPath) throws MessagingException {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, StandardCharsets.UTF_8.name());

        MimeBodyPart attachmentPart = new MimeBodyPart();
        FileDataSource dataSource = new FileDataSource(attachmentPath.toFile());
        attachmentPart.setDataHandler(new DataHandler(dataSource));
        attachmentPart.setFileName(attachmentPath.getFileName().toString());

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachmentPart);
        return multipart;
    }

    private static boolean isMarkedSent(Row row) {
        return "Y".equalsIgnoreCase(cellText(row, IS_EMAIL_SENT_COLUMN));
    }

    private static void markSent(Row row) {
        Cell cell = row.getCell(IS_EMAIL_SENT_COLUMN);
        if (cell == null) {
            cell = row.createCell(IS_EMAIL_SENT_COLUMN);
        }
        cell.setCellValue("Y");
    }

    private static String cellText(Row row, int columnIndex) {
        Cell cell = row.getCell(columnIndex);
        return cell == null ? "" : EmailExtractor.normalizeWhitespace(cell.toString());
    }
}
