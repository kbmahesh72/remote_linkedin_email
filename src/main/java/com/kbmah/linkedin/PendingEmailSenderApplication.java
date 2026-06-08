package com.kbmah.linkedin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class PendingEmailSenderApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingEmailSenderApplication.class);

    private PendingEmailSenderApplication() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(AppConfig.parse(args)));
        } catch (Exception exception) {
            LOGGER.error("Email sending failed.", exception);
            System.exit(1);
        }
    }

    static int run(AppConfig config) throws Exception {
        Path outputDir = config.outputDir().toAbsolutePath().normalize();

        while (true) {
            try {
                int sentCount = sendPendingEmailsForToday(config, outputDir);
                LOGGER.info("Email sender cycle finished. Sent {} emails.", sentCount);
            } catch (GmailAuthenticationFailureException exception) {
                LOGGER.error("Stopping email sender because Gmail authentication/authorization failed.", exception);
                return 1;
            }

            if (config.runOnce()) {
                return 0;
            }

            long sleepMillis = Math.round(config.intervalMinutes() * 60_000);
            LOGGER.info("Waiting {} before checking for pending emails again.", formatDelay(sleepMillis));
            Thread.sleep(sleepMillis);
        }
    }

    private static String formatDelay(long sleepMillis) {
        if (sleepMillis < 60_000) {
            return String.format(Locale.ROOT, "%.1f seconds", sleepMillis / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f minutes", sleepMillis / 60_000.0);
    }

    static int sendPendingEmailsForToday(AppConfig config, Path outputDir) throws Exception {
        Path workbookPath = ExcelLeadRepository.outputPathForToday(outputDir);
        return sendPendingEmails(config, workbookPath);
    }

    static int sendPendingEmails(AppConfig config, Path workbookPath) throws Exception {
        if (!Files.exists(workbookPath)) {
            LOGGER.info("Workbook does not exist yet: {}", workbookPath);
            return 0;
        }

        return EmailSender.sendPendingEmails(workbookPath, config.emailConfig());
    }
}
