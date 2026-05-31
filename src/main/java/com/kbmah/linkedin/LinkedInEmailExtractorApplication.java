package com.kbmah.linkedin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class LinkedInEmailExtractorApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkedInEmailExtractorApplication.class);

    private LinkedInEmailExtractorApplication() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(AppConfig.parse(args)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted by user.");
            System.exit(130);
        } catch (Exception exception) {
            LOGGER.error("Application failed.", exception);
            System.exit(1);
        }
    }

    static int run(AppConfig config) throws IOException, InterruptedException {
        Path outputDir = config.outputDir().toAbsolutePath().normalize();
        Path profileDir = config.profileDir().toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        Files.createDirectories(profileDir);

        LOGGER.info("Using Chromium profile: {}", profileDir);
        LOGGER.info("Saving daily workbook under: {}", outputDir);

        EmailExtractor collector = new EmailExtractor();
        while (true) {
            try {
                runCycle(config, collector, outputDir, profileDir);
            } catch (GmailAuthenticationFailureException exception) {
                LOGGER.error("Stopping automation because Gmail authentication/authorization failed.", exception);
                return 1;
            } catch (Exception exception) {
                LOGGER.error("Automation cycle failed. The next cycle will retry after the configured interval.", exception);
                if (config.runOnce()) {
                    return 1;
                }
            }

            if (config.runOnce()) {
                return 0;
            }

            long sleepMillis = Math.round(config.intervalMinutes() * 60_000);
            LOGGER.info("Waiting {} before the next run.", formatDelay(sleepMillis));
            Thread.sleep(sleepMillis);
        }
    }

    private static String formatDelay(long sleepMillis) {
        if (sleepMillis < 60_000) {
            return String.format(Locale.ROOT, "%.1f seconds", sleepMillis / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f minutes", sleepMillis / 60_000.0);
    }

    private static void runCycle(AppConfig config, EmailExtractor collector, Path outputDir, Path profileDir)
            throws Exception {
        Path outputPath = ExcelLeadRepository.outputPathForToday(outputDir);
        List<Lead> leads = collector.collect(config, profileDir);
        SaveResult saveResult = ExcelLeadRepository.save(leads, outputPath);

        LOGGER.info("Saved {} new emails to {}", saveResult.savedCount(), saveResult.outputPath());
        leads.stream()
                .findFirst()
                .ifPresent(lead -> LOGGER.info("First lead: {} | {}", lead.email(), lead.subject()));

        if (saveResult.duplicateFound()) {
            LOGGER.info("Duplicate post/email rows were skipped. Continuous automation will keep running.");
            if (saveResult.maxConsecutiveDuplicates() >= 3) {
                LOGGER.info("Found {} duplicate post/email rows continuously; skipped those rows and continued.",
                        saveResult.maxConsecutiveDuplicates());
            }
        }

        int sentCount = PendingEmailSenderApplication.sendPendingEmailsForToday(config, outputDir);
        LOGGER.info("Automatic email sender finished. Sent {} emails this cycle.", sentCount);
    }
}
