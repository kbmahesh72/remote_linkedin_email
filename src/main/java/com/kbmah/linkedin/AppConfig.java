package com.kbmah.linkedin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

record AppConfig(
        String query,
        int maxScrolls,
        double scrollPauseSeconds,
        Path outputDir,
        Path profileDir,
        int loginTimeoutSeconds,
        int maxEmails,
        double intervalMinutes,
        boolean runOnce,
        EmailConfig emailConfig
) {
    private static final String DEFAULT_QUERY = "Java c2c hiring";
    private static final int DEFAULT_MAX_EMAILS = 25;
    private static final Path DEFAULT_PROFILE_DIR = Path.of("playwright-profile");
    private static final Path DEFAULT_CONFIG_PATH = Path.of("application.properties");
    private static final Path DEFAULT_SUBJECTS_DIR = Path.of("templates", "subjects");
    private static final Path DEFAULT_BODIES_DIR = Path.of("templates", "body");

    static AppConfig parse(String[] args) {
        Properties properties = loadProperties(configPath(args));
        String query = properties.getProperty("query", DEFAULT_QUERY);
        int maxScrolls = parsePositiveInt("max-scrolls", properties.getProperty("max-scrolls", "25"));
        double scrollPauseSeconds = parsePositiveDouble("scroll-pause", properties.getProperty("scroll-pause", "2.0"));
        Path outputDir = Path.of(properties.getProperty("output-dir", "automation-output"));
        Path profileDir = Path.of(properties.getProperty("profile-dir", DEFAULT_PROFILE_DIR.toString()));
        int loginTimeoutSeconds = parsePositiveInt("login-timeout", properties.getProperty("login-timeout", "300"));
        int maxEmails = parsePositiveInt("max-emails", properties.getProperty("max-emails", String.valueOf(DEFAULT_MAX_EMAILS)));
        double intervalMinutes = parsePositiveDouble("interval-minutes", properties.getProperty("interval-minutes", "5.0"));
        boolean runOnce = Boolean.parseBoolean(properties.getProperty("run-once", "false"));
        EmailConfig emailConfig = EmailConfig.from(properties);

        for (int idx = 0; idx < args.length; idx++) {
            String arg = args[idx];
            String value = null;

            if ("--config".equals(arg)) {
                idx++;
                continue;
            }
            if (arg.startsWith("--config=")) {
                continue;
            }
            if ("--run-once".equals(arg)) {
                runOnce = true;
                continue;
            }
            if (arg.contains("=")) {
                String[] parts = arg.split("=", 2);
                arg = parts[0];
                value = parts[1];
            } else if (idx + 1 < args.length) {
                value = args[++idx];
            }

            if (value == null) {
                throw new IllegalArgumentException("Missing value for " + arg);
            }

            switch (arg) {
                case "--query" -> query = value;
                case "--max-scrolls" -> maxScrolls = parsePositiveInt(arg, value);
                case "--scroll-pause" -> scrollPauseSeconds = parsePositiveDouble(arg, value);
                case "--output-dir" -> outputDir = Path.of(value);
                case "--output" -> outputDir = outputDirFromOutputArgument(value);
                case "--profile-dir" -> profileDir = Path.of(value);
                case "--login-timeout" -> loginTimeoutSeconds = parsePositiveInt(arg, value);
                case "--max-emails" -> maxEmails = parsePositiveInt(arg, value);
                case "--interval-minutes" -> intervalMinutes = parsePositiveDouble(arg, value);
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        return new AppConfig(query, maxScrolls, scrollPauseSeconds, outputDir, profileDir, loginTimeoutSeconds,
                maxEmails, intervalMinutes, runOnce, emailConfig);
    }

    private static Path configPath(String[] args) {
        for (int idx = 0; idx < args.length; idx++) {
            String arg = args[idx];
            if ("--config".equals(arg) && idx + 1 < args.length) {
                return Path.of(args[idx + 1]);
            }
            if (arg.startsWith("--config=")) {
                return Path.of(arg.split("=", 2)[1]);
            }
        }
        return DEFAULT_CONFIG_PATH;
    }

    private static Properties loadProperties(Path configPath) {
        Properties properties = new Properties();
        try (InputStream inputStream = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read packaged application.properties.", exception);
        }

        if (Files.exists(configPath)) {
            try (InputStream inputStream = Files.newInputStream(configPath)) {
                properties.load(inputStream);
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read config file: " + configPath, exception);
            }
        }
        return properties;
    }

    private static int parsePositiveInt(String arg, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(arg + " must be greater than zero.");
        }
        return parsed;
    }

    private static double parsePositiveDouble(String arg, String value) {
        double parsed = Double.parseDouble(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(arg + " must be greater than zero.");
        }
        return parsed;
    }

    private static Path outputDirFromOutputArgument(String value) {
        Path path = Path.of(value);
        Path fileName = path.getFileName();
        if (fileName != null && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            Path parent = path.getParent();
            return parent == null ? Path.of(".") : parent;
        }
        return path;
    }

    record EmailConfig(
            boolean enabled,
            boolean dryRun,
            String gmailUsername,
            String gmailAppPassword,
            String ccEmail,
            Path subjectTemplatePath,
            Path subjectVariantsPath,
            Path bodyTemplatePath,
            Path attachmentPath,
            List<EmailAccount> accounts
    ) {
        EmailConfig(
                boolean enabled,
                boolean dryRun,
                String gmailUsername,
                String gmailAppPassword,
                String ccEmail,
                Path subjectTemplatePath,
                Path subjectVariantsPath,
                Path bodyTemplatePath,
                Path attachmentPath
        ) {
            this(enabled, dryRun, gmailUsername, gmailAppPassword, ccEmail, subjectTemplatePath, subjectVariantsPath,
                    bodyTemplatePath, attachmentPath, List.of(new EmailAccount(
                            "default",
                            gmailUsername,
                            gmailAppPassword,
                            ccEmail,
                            attachmentPath,
                            "",
                            subjectTemplatePath,
                            subjectVariantsPath,
                            bodyTemplatePath
                    )));
        }

        static EmailConfig from(Properties properties) {
            String username = properties.getProperty("gmail.username", "").trim();
            String password = firstNonBlank(
                    System.getenv("GMAIL_APP_PASSWORD"),
                    properties.getProperty("gmail.app.password", "")
            );
            Path attachmentPath = optionalPath(properties.getProperty("attachment.path", ""));
            String ccEmail = properties.getProperty("cc.email", "").trim();
            List<EmailAccount> accounts = parseAccounts(properties, username, password, ccEmail, attachmentPath);
            return new EmailConfig(
                    Boolean.parseBoolean(properties.getProperty("email.enabled", "false")),
                    Boolean.parseBoolean(properties.getProperty("email.dry-run", "true")),
                    username,
                    password,
                    ccEmail,
                    Path.of(properties.getProperty("subject.template.path", "templates/subjects/default.txt")),
                    optionalPath(properties.getProperty("subject.variants.path", "")),
                    Path.of(properties.getProperty("body.template.path", "templates/body/default.txt")),
                    attachmentPath,
                    accounts
            );
        }

        boolean hasSubjectVariantsPath() {
            return subjectVariantsPath != null;
        }

        private static Path optionalPath(String value) {
            String trimmed = value == null ? "" : value.trim();
            return trimmed.isBlank() ? null : Path.of(trimmed);
        }

        private static List<EmailAccount> parseAccounts(
                Properties properties,
                String fallbackUsername,
                String fallbackPassword,
                String fallbackCcEmail,
                Path fallbackAttachmentPath
        ) {
            Path accountsFile = optionalPath(properties.getProperty("email.accounts.file", ""));
            if (accountsFile != null) {
                return parseAccountsFile(accountsFile);
            }

            String accountIds = properties.getProperty("email.accounts", "").trim();
            if (accountIds.isBlank()) {
                return List.of(new EmailAccount(
                        "default",
                        fallbackUsername,
                        fallbackPassword,
                        fallbackCcEmail,
                        fallbackAttachmentPath,
                        "",
                        null,
                        null,
                        null
                ));
            }

            return Arrays.stream(accountIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isBlank())
                    .map(id -> parseAccount(properties, id))
                    .toList();
        }

        private static EmailAccount parseAccount(Properties properties, String id) {
            String prefix = "email.account." + id + ".";
            String username = properties.getProperty(prefix + "username", "").trim();
            String password = firstNonBlank(
                    System.getenv(envPasswordName(id)),
                    properties.getProperty(prefix + "app.password", "")
            );
            Path attachmentPath = optionalPath(properties.getProperty(prefix + "attachment.path", ""));
            String ccEmail = properties.getProperty(prefix + "cc.email", "").trim();
            String subjectPrefix = properties.getProperty(prefix + "subject.prefix", "");
            Path subjectPath = subjectPath(username, properties.getProperty(prefix + "subject.path", ""));
            Path subjectVariantsPath = subjectVariantsPath(username, properties.getProperty(prefix + "subject.variants.path", ""));
            Path bodyPath = bodyPath(username, properties.getProperty(prefix + "body.path", ""));
            return new EmailAccount(id, username, password, ccEmail, attachmentPath,
                    subjectPrefix, subjectPath, subjectVariantsPath, bodyPath);
        }

        private static List<EmailAccount> parseAccountsFile(Path accountsFile) {
            if (!Files.isRegularFile(accountsFile)) {
                throw new IllegalStateException("Email accounts file not found: " + accountsFile);
            }

            try {
                List<EmailAccount> accounts = new ArrayList<>();
                List<String> lines = Files.readAllLines(accountsFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank() || trimmed.startsWith("#") || isAccountsHeader(trimmed)) {
                        continue;
                    }

                    List<String> columns = parseCsvLine(line);
                    if (columns.size() < 8) {
                        throw new IllegalStateException("Invalid email account row in " + accountsFile
                                + ". Expected: id,username,appPassword,ccEmail,attachmentPath,subjectPrefix,subjectPath,subjectVariantsPath,bodyPath");
                    }

                    String id = columns.get(0).trim();
                    String username = columns.get(1).trim();
                    String password = firstNonBlank(System.getenv(envPasswordName(id)), columns.get(2));
                    String ccEmail = columns.get(3).trim();
                    Path attachmentPath = optionalPath(columns.get(4));
                    String subjectPrefix = columns.size() > 5 ? decodeEscapes(columns.get(5)) : "";
                    Path subjectPath = columns.size() > 6 ? subjectPath(username, columns.get(6)) : subjectPath(username, "");
                    Path subjectVariantsPath = columns.size() > 7 ? subjectVariantsPath(username, columns.get(7)) : null;
                    Path bodyPath = columns.size() > 8 ? bodyPath(username, columns.get(8)) : bodyPath(username, "");
                    accounts.add(new EmailAccount(id, username, password, ccEmail, attachmentPath,
                            subjectPrefix, subjectPath, subjectVariantsPath, bodyPath));
                }

                if (accounts.isEmpty()) {
                    throw new IllegalStateException("Email accounts file has no account rows: " + accountsFile);
                }
                return List.copyOf(accounts);
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read email accounts file: " + accountsFile, exception);
            }
        }

        private static boolean isAccountsHeader(String line) {
            String lowerLine = line.toLowerCase(Locale.ROOT);
            return lowerLine.startsWith("id,username,")
                    || lowerLine.startsWith("account,username,");
        }

        private static List<String> parseCsvLine(String line) {
            List<String> columns = new ArrayList<>();
            StringBuilder column = new StringBuilder();
            boolean quoted = false;

            for (int idx = 0; idx < line.length(); idx++) {
                char current = line.charAt(idx);
                if (current == '"') {
                    if (quoted && idx + 1 < line.length() && line.charAt(idx + 1) == '"') {
                        column.append('"');
                        idx++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (current == ',' && !quoted) {
                    columns.add(column.toString());
                    column.setLength(0);
                } else {
                    column.append(current);
                }
            }
            columns.add(column.toString());
            return columns;
        }

        private static String decodeEscapes(String value) {
            return value.replace("\\n", System.lineSeparator());
        }

        private static Path subjectPath(String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return DEFAULT_SUBJECTS_DIR.resolve(emailFileName(username));
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return DEFAULT_SUBJECTS_DIR.resolve(path);
        }

        private static Path subjectVariantsPath(String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return null;
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return DEFAULT_SUBJECTS_DIR.resolve(path);
        }

        private static Path bodyPath(String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return DEFAULT_BODIES_DIR.resolve(emailFileName(username));
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return DEFAULT_BODIES_DIR.resolve(path);
        }

        private static String emailFileName(String username) {
            String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                return "default.txt";
            }
            return normalized + ".txt";
        }

        private static String envPasswordName(String id) {
            String normalized = id.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
            return "GMAIL_APP_PASSWORD_" + normalized;
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first.trim() : second.trim();
        }
    }

    record EmailAccount(
            String id,
            String gmailUsername,
            String gmailAppPassword,
            String ccEmail,
            Path attachmentPath,
            String subjectPrefix,
            Path subjectTemplatePath,
            Path subjectVariantsPath,
            Path bodyTemplatePath
    ) {
        boolean hasAttachmentPath() {
            return attachmentPath != null;
        }
    }
}
