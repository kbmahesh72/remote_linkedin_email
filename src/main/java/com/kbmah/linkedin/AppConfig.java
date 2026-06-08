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
import java.util.regex.Pattern;

record AppConfig(
        String query,
        List<String> queries,
        int maxScrolls,
        double scrollPauseSeconds,
        Path outputDir,
        Path profileDir,
        int loginTimeoutSeconds,
        int maxEmails,
        double intervalMinutes,
        boolean runOnce,
        BrowserConfig browserConfig,
        EmailConfig emailConfig
) {
    private static final Path DEFAULT_CONFIG_PATH = Path.of("application.properties");

    static AppConfig parse(String[] args) {
        Properties properties = loadProperties(configPath(args));
        List<String> queries = parseQueries(requiredProperty(properties, "queries"));
        String query = queries.get(0);
        int maxScrolls = parsePositiveInt("max-scrolls", requiredProperty(properties, "max-scrolls"));
        double scrollPauseSeconds = parsePositiveDouble("scroll-pause", requiredProperty(properties, "scroll-pause"));
        Path outputDir = Path.of(requiredProperty(properties, "output-dir"));
        Path profileDir = Path.of(requiredProperty(properties, "profile-dir"));
        int loginTimeoutSeconds = parsePositiveInt("login-timeout", requiredProperty(properties, "login-timeout"));
        int maxEmails = parsePositiveInt("max-emails", requiredProperty(properties, "max-emails"));
        double intervalMinutes = parsePositiveDouble("interval-minutes", requiredProperty(properties, "interval-minutes"));
        boolean runOnce = parseBoolean("run-once", requiredProperty(properties, "run-once"));
        BrowserConfig browserConfig = BrowserConfig.from(properties);
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
            if ("--headless".equals(arg)) {
                browserConfig = browserConfig.withHeadless(true);
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
                case "--query" -> {
                    query = value;
                    queries = List.of(value);
                }
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

        return new AppConfig(query, queries, maxScrolls, scrollPauseSeconds, outputDir, profileDir, loginTimeoutSeconds,
                maxEmails, intervalMinutes, runOnce, browserConfig, emailConfig);
    }

    AppConfig withQuery(String selectedQuery) {
        return new AppConfig(selectedQuery, queries, maxScrolls, scrollPauseSeconds, outputDir, profileDir,
                loginTimeoutSeconds, maxEmails, intervalMinutes, runOnce, browserConfig, emailConfig);
    }

    static BrowserConfig defaultBrowserConfig() {
        return BrowserConfig.from(loadProperties(DEFAULT_CONFIG_PATH));
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
        loadPropertiesFile(properties, configPath);
        return properties;
    }

    private static void loadPropertiesFile(Properties properties, Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalStateException("Configuration file not found: " + configPath.toAbsolutePath());
        }

        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read config file: " + configPath, exception);
        }
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

    private static boolean parseBoolean(String arg, String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(arg + " must be true or false.");
        }
        return Boolean.parseBoolean(value);
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration property: " + key);
        }
        return value.trim();
    }

    private static List<String> parseQueries(String value) {
        List<String> queries = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(query -> !query.isBlank())
                .toList();
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries must contain at least one search keyword.");
        }
        return queries;
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

    record BrowserConfig(
            boolean headless,
            int viewportWidth,
            int viewportHeight,
            String linkedinHomeUrl,
            String linkedinLoginUrl,
            String linkedinSearchUrlTemplate,
            String linkedinEmailEnvVar,
            String linkedinPasswordEnvVar,
            Pattern blockedPostKeywords,
            int maxUnchangedScrollRounds,
            int scrollPixels,
            int postSettleMillis,
            int pageSettleMillis,
            int searchSettleMillis,
            int loginSubmitWaitMillis,
            int manualLoginPollMillis,
            int uiSettleMillis,
            int expandSettleMillis,
            int clickTimeoutMillis,
            int postsClickTimeoutMillis,
            int operationTimeoutMillis
    ) {
        static BrowserConfig from(Properties properties) {
            return new BrowserConfig(
                    parseBoolean("browser.headless", requiredProperty(properties, "browser.headless")),
                    parsePositiveInt("browser.viewport.width", requiredProperty(properties, "browser.viewport.width")),
                    parsePositiveInt("browser.viewport.height", requiredProperty(properties, "browser.viewport.height")),
                    requiredProperty(properties, "linkedin.home-url"),
                    requiredProperty(properties, "linkedin.login-url"),
                    requiredProperty(properties, "linkedin.search-url-template"),
                    requiredProperty(properties, "linkedin.email-env-var"),
                    requiredProperty(properties, "linkedin.password-env-var"),
                    keywordPattern(requiredProperty(properties, "blocked-post-keywords")),
                    parsePositiveInt("browser.max-unchanged-scroll-rounds",
                            requiredProperty(properties, "browser.max-unchanged-scroll-rounds")),
                    parsePositiveInt("browser.scroll-pixels", requiredProperty(properties, "browser.scroll-pixels")),
                    parsePositiveInt("browser.post-settle-ms", requiredProperty(properties, "browser.post-settle-ms")),
                    parsePositiveInt("browser.page-settle-ms", requiredProperty(properties, "browser.page-settle-ms")),
                    parsePositiveInt("browser.search-settle-ms", requiredProperty(properties, "browser.search-settle-ms")),
                    parsePositiveInt("browser.login-submit-wait-ms",
                            requiredProperty(properties, "browser.login-submit-wait-ms")),
                    parsePositiveInt("browser.manual-login-poll-ms",
                            requiredProperty(properties, "browser.manual-login-poll-ms")),
                    parsePositiveInt("browser.ui-settle-ms", requiredProperty(properties, "browser.ui-settle-ms")),
                    parsePositiveInt("browser.expand-settle-ms", requiredProperty(properties, "browser.expand-settle-ms")),
                    parsePositiveInt("browser.click-timeout-ms", requiredProperty(properties, "browser.click-timeout-ms")),
                    parsePositiveInt("browser.posts-click-timeout-ms",
                            requiredProperty(properties, "browser.posts-click-timeout-ms")),
                    parsePositiveInt("browser.operation-timeout-ms",
                            requiredProperty(properties, "browser.operation-timeout-ms"))
            );
        }

        BrowserConfig withHeadless(boolean value) {
            return new BrowserConfig(value, viewportWidth, viewportHeight, linkedinHomeUrl, linkedinLoginUrl,
                    linkedinSearchUrlTemplate, linkedinEmailEnvVar, linkedinPasswordEnvVar, blockedPostKeywords,
                    maxUnchangedScrollRounds, scrollPixels, postSettleMillis, pageSettleMillis, searchSettleMillis,
                    loginSubmitWaitMillis, manualLoginPollMillis, uiSettleMillis, expandSettleMillis,
                    clickTimeoutMillis, postsClickTimeoutMillis, operationTimeoutMillis);
        }

        private static Pattern keywordPattern(String commaSeparatedKeywords) {
            String expression = Arrays.stream(commaSeparatedKeywords.split(","))
                    .map(String::trim)
                    .filter(keyword -> !keyword.isBlank())
                    .map(Pattern::quote)
                    .reduce((left, right) -> left + "|" + right)
                    .orElseThrow(() -> new IllegalArgumentException("blocked-post-keywords must not be empty."));
            return Pattern.compile("\\b(?:" + expression + ")\\b", Pattern.CASE_INSENSITIVE);
        }
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
            List<EmailAccount> accounts,
            SmtpConfig smtpConfig
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
                Path attachmentPath,
                SmtpConfig smtpConfig
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
                    )), smtpConfig);
        }

        static EmailConfig from(Properties properties) {
            String username = properties.getProperty("gmail.username", "").trim();
            String passwordEnvVar = requiredProperty(properties, "gmail.app-password-env-var");
            String accountPasswordEnvPrefix = requiredProperty(properties, "gmail.account-password-env-prefix");
            String password = firstNonBlank(
                    System.getenv(passwordEnvVar),
                    properties.getProperty("gmail.app.password", "")
            );
            Path attachmentPath = optionalPath(properties.getProperty("attachment.path", ""));
            String ccEmail = properties.getProperty("cc.email", "").trim();
            Path subjectsDir = Path.of(requiredProperty(properties, "subject.templates.dir"));
            Path bodiesDir = Path.of(requiredProperty(properties, "body.templates.dir"));
            List<EmailAccount> accounts = parseAccounts(
                    properties, username, password, ccEmail, attachmentPath, subjectsDir, bodiesDir,
                    accountPasswordEnvPrefix
            );
            return new EmailConfig(
                    parseBoolean("email.enabled", requiredProperty(properties, "email.enabled")),
                    parseBoolean("email.dry-run", requiredProperty(properties, "email.dry-run")),
                    username,
                    password,
                    ccEmail,
                    Path.of(requiredProperty(properties, "subject.template.path")),
                    optionalPath(properties.getProperty("subject.variants.path", "")),
                    Path.of(requiredProperty(properties, "body.template.path")),
                    attachmentPath,
                    accounts,
                    SmtpConfig.from(properties)
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
                Path fallbackAttachmentPath,
                Path subjectsDir,
                Path bodiesDir,
                String accountPasswordEnvPrefix
        ) {
            Path accountsFile = optionalPath(properties.getProperty("email.accounts.file", ""));
            if (accountsFile != null) {
                return parseAccountsFile(accountsFile, subjectsDir, bodiesDir, accountPasswordEnvPrefix);
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
                    .map(id -> parseAccount(properties, id, subjectsDir, bodiesDir, accountPasswordEnvPrefix))
                    .toList();
        }

        private static EmailAccount parseAccount(
                Properties properties,
                String id,
                Path subjectsDir,
                Path bodiesDir,
                String accountPasswordEnvPrefix
        ) {
            String prefix = "email.account." + id + ".";
            String username = properties.getProperty(prefix + "username", "").trim();
            String password = firstNonBlank(
                    System.getenv(envPasswordName(accountPasswordEnvPrefix, id)),
                    properties.getProperty(prefix + "app.password", "")
            );
            Path attachmentPath = optionalPath(properties.getProperty(prefix + "attachment.path", ""));
            String ccEmail = properties.getProperty(prefix + "cc.email", "").trim();
            String subjectPrefix = properties.getProperty(prefix + "subject.prefix", "");
            Path subjectPath = subjectPath(subjectsDir, username, properties.getProperty(prefix + "subject.path", ""));
            Path subjectVariantsPath = subjectVariantsPath(
                    subjectsDir, username, properties.getProperty(prefix + "subject.variants.path", "")
            );
            Path bodyPath = bodyPath(bodiesDir, username, properties.getProperty(prefix + "body.path", ""));
            return new EmailAccount(id, username, password, ccEmail, attachmentPath,
                    subjectPrefix, subjectPath, subjectVariantsPath, bodyPath);
        }

        private static List<EmailAccount> parseAccountsFile(
                Path accountsFile,
                Path subjectsDir,
                Path bodiesDir,
                String accountPasswordEnvPrefix
        ) {
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
                    String password = firstNonBlank(
                            System.getenv(envPasswordName(accountPasswordEnvPrefix, id)),
                            columns.get(2)
                    );
                    String ccEmail = columns.get(3).trim();
                    Path attachmentPath = optionalPath(columns.get(4));
                    String subjectPrefix = columns.size() > 5 ? decodeEscapes(columns.get(5)) : "";
                    Path subjectPath = columns.size() > 6
                            ? subjectPath(subjectsDir, username, columns.get(6))
                            : subjectPath(subjectsDir, username, "");
                    Path subjectVariantsPath = columns.size() > 7
                            ? subjectVariantsPath(subjectsDir, username, columns.get(7))
                            : null;
                    Path bodyPath = columns.size() > 8
                            ? bodyPath(bodiesDir, username, columns.get(8))
                            : bodyPath(bodiesDir, username, "");
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

        private static Path subjectPath(Path subjectsDir, String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return subjectsDir.resolve(emailFileName(username));
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return subjectsDir.resolve(path);
        }

        private static Path subjectVariantsPath(Path subjectsDir, String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return null;
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return subjectsDir.resolve(path);
        }

        private static Path bodyPath(Path bodiesDir, String username, String value) {
            Path path = optionalPath(value);
            if (path == null) {
                return bodiesDir.resolve(emailFileName(username));
            }
            if (path.isAbsolute() || path.getParent() != null) {
                return path;
            }
            return bodiesDir.resolve(path);
        }

        private static String emailFileName(String username) {
            String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                return "default.txt";
            }
            return normalized + ".txt";
        }

        private static String envPasswordName(String prefix, String id) {
            String normalized = id.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
            return prefix + normalized;
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first.trim() : second.trim();
        }
    }

    record SmtpConfig(boolean auth, boolean startTls, String host, int port) {
        static SmtpConfig from(Properties properties) {
            return new SmtpConfig(
                    parseBoolean("smtp.auth", requiredProperty(properties, "smtp.auth")),
                    parseBoolean("smtp.starttls.enable", requiredProperty(properties, "smtp.starttls.enable")),
                    requiredProperty(properties, "smtp.host"),
                    parsePositiveInt("smtp.port", requiredProperty(properties, "smtp.port"))
            );
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
