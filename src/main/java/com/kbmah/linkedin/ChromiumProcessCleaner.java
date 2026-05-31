package com.kbmah.linkedin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class ChromiumProcessCleaner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChromiumProcessCleaner.class);

    private ChromiumProcessCleaner() {
    }

    static void closeProcessesForProfile(Path profileDir) {
        String profilePath = profileDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        closeMatchingProcesses(commandLine -> commandLine.contains(profilePath));
    }

    static void closePlaywrightChromiumProcesses() {
        closeMatchingProcesses(commandLine ->
                commandLine.contains("ms-playwright")
                        && commandLine.contains("chrom")
        );
    }

    private static void closeMatchingProcesses(Predicate<String> commandLineMatcher) {
        ProcessHandle.allProcesses()
                .filter(process -> commandLineMatcher.test(commandLine(process)))
                .forEach(ChromiumProcessCleaner::closeProcess);
    }

    private static String commandLine(ProcessHandle process) {
        return process.info()
                .commandLine()
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }

    private static void closeProcess(ProcessHandle process) {
        if (!process.isAlive()) {
            return;
        }

        LOGGER.debug("Closing leftover Chromium process {}.", process.pid());
        process.destroy();
        try {
            process.onExit().get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            forceClose(process);
        } catch (Exception exception) {
            forceClose(process);
        }
    }

    private static void forceClose(ProcessHandle process) {
        if (process.isAlive()) {
            LOGGER.debug("Forcibly closing leftover Chromium process {}.", process.pid());
            process.destroyForcibly();
        }
    }
}
