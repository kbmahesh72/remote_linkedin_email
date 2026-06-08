package com.kbmah.linkedin;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmailExtractor {
    static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailExtractor.class);

    static String extractFirstName(String email) {
        String localPart = email.split("@", 2)[0].trim();
        String firstToken = localPart.contains(".") ? localPart.split("\\.", 2)[0].trim() : localPart;
        firstToken = firstToken.split("[_+\\-]", 2)[0].trim();
        firstToken = firstToken.replaceAll("\\d+", "").trim();
        if (firstToken.isEmpty()) {
            return "";
        }
        return firstToken.substring(0, 1).toUpperCase(Locale.ROOT)
                + firstToken.substring(1).toLowerCase(Locale.ROOT);
    }

    static List<Lead> extractLeads(String postText, String query) {
        return extractLeads(postText, query, AppConfig.defaultBrowserConfig().blockedPostKeywords());
    }

    static List<Lead> extractLeads(String postText, String query, Pattern blockedPostKeywords) {
        if (hasBlockedKeyword(postText, blockedPostKeywords)) {
            return List.of();
        }

        Matcher matcher = EMAIL_PATTERN.matcher(postText);
        Set<String> emails = new HashSet<>();
        while (matcher.find()) {
            emails.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        if (emails.isEmpty()) {
            return List.of();
        }

        String subject = buildSubject(postText, query);
        return emails.stream()
                .sorted()
                .map(email -> new Lead(email, subject))
                .toList();
    }

    private static boolean hasBlockedKeyword(String postText, Pattern blockedPostKeywords) {
        return postText != null && blockedPostKeywords.matcher(postText).find();
    }

    static String buildSubject(String postText, String query) {
        String cleaned = normalizeWhitespace(postText);
        if (cleaned.isEmpty()) {
            return query + " opportunity";
        }

        cleaned = cleaned.replaceFirst("(?i)^Feed post\\s+", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+(?:Like|Comment|Repost|Send)\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)\\s+\\d+\\s*(?:reactions?|comments?)\\b.*$", "");
        cleaned = cleaned.replaceFirst("(?i)^\\s*[^|]{0,120}?\\b(?:Follow|Join)\\b\\s*", "");
        cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)\\b(?:more|see more|show more)\\b", "");
        cleaned = normalizeWhitespace(cleaned);

        String candidate = firstUsefulSentence(cleaned);
        if (candidate.isEmpty()) {
            candidate = cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
            candidate = candidate.strip().replaceAll("^[\\s\\-|]+|[\\s\\-|]+$", "");
            if (candidate.isEmpty()) {
                candidate = query;
            }
        }

        if (candidate.length() > 90) {
            candidate = candidate.substring(0, 90);
        }
        candidate = candidate.replaceAll("[ ,;:\\-]+$", "");
        if (!candidate.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            return query + " - " + candidate;
        }
        return candidate;
    }

    static String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    List<Lead> collect(AppConfig config, Path profileDir) {
        List<Lead> collectedLeads;
        ChromiumProcessCleaner.closeProcessesForProfile(profileDir);
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchPersistentContextOptions launchOptions =
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(config.browserConfig().headless())
                            .setViewportSize(
                                    config.browserConfig().viewportWidth(),
                                    config.browserConfig().viewportHeight()
                            );

            BrowserContext context = playwright.chromium().launchPersistentContext(profileDir, launchOptions);
            try {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                ensureLogin(page, config);
                searchPosts(page, config);
                applyLatestSort(page, config.browserConfig());
                collectedLeads = collectLeadsFromFeed(config, page);
            } finally {
                closeQuietly(context);
            }
        } finally {
            ChromiumProcessCleaner.closeProcessesForProfile(profileDir);
        }
        LOGGER.info("LinkedIn browser closed after extracting {} emails.", collectedLeads.size());
        return collectedLeads;
    }

    private static void closeQuietly(BrowserContext context) {
        try {
            for (Page page : context.pages()) {
                closeQuietly(page);
            }
            context.close();
        } catch (Exception exception) {
            LOGGER.debug("Browser context was already closed.", exception);
        }
    }

    private static void closeQuietly(Page page) {
        try {
            page.close(new Page.CloseOptions().setRunBeforeUnload(false));
        } catch (Exception exception) {
            LOGGER.debug("Browser page was already closed.", exception);
        }
    }

    private static List<Lead> collectLeadsFromFeed(AppConfig config, Page page) {
        Set<String> processedPosts = new HashSet<>();
        Map<String, Lead> leadsByPostAndEmail = new LinkedHashMap<>();
        int unchangedRounds = 0;

        for (int scroll = 0; scroll < config.maxScrolls(); scroll++) {
            Locator articles = locatePostCards(page);
            int articleCount = articles.count();
            int processedBeforeRound = processedPosts.size();
            LOGGER.info("Scroll {}/{}: scanning {} visible post cards.", scroll + 1, config.maxScrolls(), articleCount);

            for (int idx = 0; idx < articleCount; idx++) {
                collectArticleLeads(page, articles.nth(idx), config, processedPosts, leadsByPostAndEmail);
                if (leadsByPostAndEmail.size() >= config.maxEmails()) {
                    return sortedLeads(leadsByPostAndEmail);
                }
            }

            unchangedRounds = processedPosts.size() == processedBeforeRound ? unchangedRounds + 1 : 0;
            if (unchangedRounds >= config.browserConfig().maxUnchangedScrollRounds()) {
                break;
            }

            page.mouse().wheel(0, config.browserConfig().scrollPixels());
            page.waitForTimeout(config.scrollPauseSeconds() * 1000);
        }

        return sortedLeads(leadsByPostAndEmail);
    }

    private static void collectArticleLeads(
            Page page,
            Locator article,
            AppConfig config,
            Set<String> processedPosts,
            Map<String, Lead> leadsByPostAndEmail
    ) {
        try {
            article.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions()
                    .setTimeout(config.browserConfig().operationTimeoutMillis()));
            page.waitForTimeout(config.browserConfig().postSettleMillis());
        } catch (Exception exception) {
            return;
        }

        String fingerprint = articleFingerprint(article, config.browserConfig());
        if (fingerprint.isEmpty() || processedPosts.contains(fingerprint)) {
            return;
        }

        expandPostContent(page, article, config.browserConfig());
        try {
            String postText = EmailExtractor.normalizeWhitespace(
                    article.innerText(new Locator.InnerTextOptions()
                            .setTimeout(config.browserConfig().operationTimeoutMillis()))
            );
            if (!postText.isEmpty()) {
                addLeadMatches(
                        postText,
                        config.query(),
                        config.browserConfig().blockedPostKeywords(),
                        leadsByPostAndEmail
                );
            }
        } catch (Exception exception) {
            LOGGER.debug("Could not read post text.", exception);
        } finally {
            processedPosts.add(fingerprint);
        }
    }

    private static void addLeadMatches(
            String postText,
            String query,
            Pattern blockedPostKeywords,
            Map<String, Lead> leadsByPostAndEmail
    ) {
        for (Lead lead : EmailExtractor.extractLeads(postText, query, blockedPostKeywords)) {
            leadsByPostAndEmail.putIfAbsent(ExcelLeadRepository.postEmailKey(lead.subject(), lead.email()), lead);
        }
    }

    private static List<Lead> sortedLeads(Map<String, Lead> leadsByPostAndEmail) {
        return leadsByPostAndEmail.values().stream()
                .sorted(Comparator.comparing(Lead::email).thenComparing(Lead::subject))
                .toList();
    }

    private static boolean isLoginPage(Page page, AppConfig.BrowserConfig browserConfig) {
        String currentUrl = page.url().toLowerCase(Locale.ROOT);
        if (currentUrl.contains("login") || currentUrl.contains("checkpoint") || currentUrl.contains("/uas/")) {
            return true;
        }

        for (String selector : List.of(
                "input[name='session_key']",
                "input[name='session_password']",
                "button:has-text('Sign in')",
                "a:has-text('Sign in')"
        )) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.isVisible(
                        new Locator.IsVisibleOptions().setTimeout(browserConfig.operationTimeoutMillis())
                )) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean attemptCredentialLogin(Page page, AppConfig.BrowserConfig browserConfig) {
        String email = System.getenv().getOrDefault(browserConfig.linkedinEmailEnvVar(), "").trim();
        String password = System.getenv().getOrDefault(browserConfig.linkedinPasswordEnvVar(), "");
        if (email.isEmpty() || password.isEmpty()) {
            return false;
        }

        try {
            if (!page.url().toLowerCase(Locale.ROOT).contains("linkedin.com/login")) {
                navigate(page, browserConfig.linkedinLoginUrl());
                page.waitForTimeout(browserConfig.uiSettleMillis());
            }

            Locator emailInput = page.locator("input[name='session_key'], input#username").first();
            Locator passwordInput = page.locator("input[name='session_password'], input#password").first();
            if (emailInput.count() == 0 || passwordInput.count() == 0) {
                return false;
            }

            emailInput.fill(email, new Locator.FillOptions().setTimeout(browserConfig.operationTimeoutMillis()));
            passwordInput.fill(password, new Locator.FillOptions().setTimeout(browserConfig.operationTimeoutMillis()));
            submitLogin(page, passwordInput, browserConfig);
            page.waitForTimeout(browserConfig.loginSubmitWaitMillis());
            return !isLoginPage(page, browserConfig);
        } catch (Exception exception) {
            LOGGER.warn("Automatic LinkedIn login failed; manual login may be required.");
            LOGGER.debug("Automatic login failure details.", exception);
            return false;
        }
    }

    private static void submitLogin(
            Page page,
            Locator passwordInput,
            AppConfig.BrowserConfig browserConfig
    ) {
        for (String selector : List.of(
                "button[type='submit']",
                "button[aria-label='Sign in']",
                "button.login__form_action_container",
                "button:has-text('Sign in')"
        )) {
            try {
                Locator button = page.locator(selector).first();
                if (button.count() > 0) {
                    button.click(new Locator.ClickOptions().setTimeout(browserConfig.clickTimeoutMillis()));
                    return;
                }
            } catch (Exception exception) {
                if (page.url().toLowerCase(Locale.ROOT).contains("/feed")
                        || !isLoginPage(page, browserConfig)) {
                    return;
                }
            }
        }
        passwordInput.press("Enter", new Locator.PressOptions().setTimeout(browserConfig.operationTimeoutMillis()));
    }

    private static void waitForManualLogin(
            Page page,
            int timeoutSeconds,
            AppConfig.BrowserConfig browserConfig
    ) {
        int deadlineMs = timeoutSeconds * 1000;
        int elapsedMs = 0;
        int pollMs = browserConfig.manualLoginPollMillis();

        LOGGER.info("Manual LinkedIn login required in the opened browser window.");
        LOGGER.info("Waiting up to {} seconds for sign-in to complete.", timeoutSeconds);
        while (elapsedMs < deadlineMs) {
            page.waitForTimeout(pollMs);
            elapsedMs += pollMs;
            try {
                navigate(page, browserConfig.linkedinHomeUrl());
                page.waitForTimeout(browserConfig.uiSettleMillis());
            } catch (Exception ignored) {
                continue;
            }
            if (!isLoginPage(page, browserConfig)) {
                return;
            }
        }

        throw new IllegalStateException("LinkedIn login did not complete before the timeout.");
    }

    private static void ensureLogin(Page page, AppConfig config) {
        AppConfig.BrowserConfig browserConfig = config.browserConfig();
        navigate(page, browserConfig.linkedinHomeUrl());
        page.waitForTimeout(browserConfig.pageSettleMillis());

        if (!isLoginPage(page, browserConfig)) {
            return;
        }

        navigate(page, browserConfig.linkedinLoginUrl());
        page.waitForTimeout(browserConfig.pageSettleMillis());
        if (attemptCredentialLogin(page, browserConfig)) {
            navigate(page, browserConfig.linkedinHomeUrl());
            page.waitForTimeout(browserConfig.pageSettleMillis());
            return;
        }

        if (browserConfig.headless()) {
            throw new IllegalStateException(
                    "LinkedIn login is required, but Chromium is running headlessly. "
                            + "Use an authenticated profile or set "
                            + browserConfig.linkedinEmailEnvVar() + " and "
                            + browserConfig.linkedinPasswordEnvVar() + "."
            );
        }

        waitForManualLogin(page, config.loginTimeoutSeconds(), browserConfig);
        if (isLoginPage(page, browserConfig)) {
            throw new IllegalStateException("LinkedIn login is still required. Please complete sign-in and rerun the app.");
        }
    }

    private static void searchPosts(Page page, AppConfig config) {
        AppConfig.BrowserConfig browserConfig = config.browserConfig();
        String encodedQuery = URLEncoder.encode(config.query(), StandardCharsets.UTF_8).replace("+", "%20");
        navigate(page, browserConfig.linkedinSearchUrlTemplate().formatted(encodedQuery));
        page.waitForTimeout(browserConfig.searchSettleMillis());
        if (isLoginPage(page, browserConfig)) {
            throw new IllegalStateException("LinkedIn redirected to the login page before search results loaded.");
        }
        safeClickPosts(page, browserConfig);
    }

    private static void applyLatestSort(Page page, AppConfig.BrowserConfig browserConfig) {
        try {
            if (openSortByFilter(page, browserConfig)) {
                selectLatestSort(page, browserConfig);
                try {
                    clickShowResults(page, browserConfig);
                    return;
                } catch (IllegalStateException ignored) {
                    page.waitForTimeout(browserConfig.uiSettleMillis());
                }
            }

            openAllFilters(page, browserConfig);
            selectLatestSort(page, browserConfig);
            clickShowResults(page, browserConfig);
        } catch (IllegalStateException exception) {
            LOGGER.warn("Could not apply LinkedIn Latest filter; continuing with current post order.");
        }
    }

    private static boolean clickFirstMatching(Page page, List<String> selectors, int timeoutMs) {
        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0) {
                    locator.click(new Locator.ClickOptions().setTimeout(timeoutMs));
                    return true;
                }
            } catch (TimeoutError ignored) {
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static void safeClickPosts(Page page, AppConfig.BrowserConfig browserConfig) {
        if (clickExactLabel(
                page,
                List.of("button", "a", "[role='tab']"),
                "Posts",
                browserConfig.postsClickTimeoutMillis(),
                browserConfig.operationTimeoutMillis()
        )) {
            page.waitForTimeout(browserConfig.uiSettleMillis());
            return;
        }

        if (page.url().toLowerCase(Locale.ROOT).contains("/search/results/content")) {
            LOGGER.info("LinkedIn Posts tab was not visible, but content search results are already loaded.");
            return;
        }

        throw new IllegalStateException("Could not find or click the LinkedIn 'Posts' tab.");
    }

    private static boolean clickExactLabel(
            Page page,
            List<String> selectors,
            String expectedText,
            int timeoutMs,
            int operationTimeoutMillis
    ) {
        for (String selector : selectors) {
            try {
                Locator candidates = page.locator(selector);
                int count = candidates.count();
                for (int idx = 0; idx < count; idx++) {
                    Locator candidate = candidates.nth(idx);
                    String label = EmailExtractor.normalizeWhitespace(
                            candidate.innerText(new Locator.InnerTextOptions().setTimeout(operationTimeoutMillis))
                    );
                    if (isExpectedTabLabel(label, expectedText)) {
                        candidate.click(new Locator.ClickOptions().setTimeout(timeoutMs));
                        return true;
                    }
                }
            } catch (TimeoutError ignored) {
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean isExpectedTabLabel(String label, String expectedText) {
        String normalized = EmailExtractor.normalizeWhitespace(label);
        String lowerLabel = normalized.toLowerCase(Locale.ROOT);
        if (lowerLabel.contains("repost")) {
            return false;
        }

        String lowerExpected = expectedText.toLowerCase(Locale.ROOT);
        return lowerLabel.equals(lowerExpected) || lowerLabel.startsWith(lowerExpected + " ");
    }

    private static boolean openSortByFilter(Page page, AppConfig.BrowserConfig browserConfig) {
        boolean opened = clickFirstMatching(page, List.of(
                "button:has-text('Sort by')",
                "[role='button']:has-text('Sort by')",
                "[aria-label*='Sort by']",
                "text=Sort by"
        ), browserConfig.clickTimeoutMillis());
        if (opened) {
            page.waitForTimeout(browserConfig.uiSettleMillis());
        }
        return opened;
    }

    private static void openAllFilters(Page page, AppConfig.BrowserConfig browserConfig) {
        if (!clickFirstMatching(page, List.of(
                "button:has-text('All filters')",
                "[role='button']:has-text('All filters')",
                "[aria-label*='All filters']",
                "text=All filters"
        ), browserConfig.clickTimeoutMillis())) {
            throw new IllegalStateException("Could not open LinkedIn filters.");
        }
        page.waitForTimeout(browserConfig.uiSettleMillis());
    }

    private static void selectLatestSort(Page page, AppConfig.BrowserConfig browserConfig) {
        if (!clickFirstMatching(page, List.of(
                "label:has-text('Latest')",
                "input[value='date_posted']",
                "input[value='latest']",
                "[role='option']:has-text('Latest')",
                "[role='radio']:has-text('Latest')",
                "text=Latest"
        ), browserConfig.clickTimeoutMillis())) {
            throw new IllegalStateException("Could not select the 'Latest' sort option.");
        }
        page.waitForTimeout(browserConfig.uiSettleMillis());
    }

    private static void clickShowResults(Page page, AppConfig.BrowserConfig browserConfig) {
        if (!clickFirstMatching(page, List.of(
                "button:has-text('Show results')",
                "[aria-label*='Show results']",
                "text=Show results"
        ), browserConfig.clickTimeoutMillis())) {
            throw new IllegalStateException("Could not click the LinkedIn 'Show results' button.");
        }
        page.waitForTimeout(browserConfig.pageSettleMillis());
    }

    static void expandPostContent(Page page, Locator article) {
        expandPostContent(page, article, AppConfig.defaultBrowserConfig());
    }

    static void expandPostContent(Page page, Locator article, AppConfig.BrowserConfig browserConfig) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String beforeClick = innerTextQuietly(article, browserConfig);
            int clickedCount = clickPostExpandButtons(page, article, browserConfig);
            if (clickedCount == 0) {
                return;
            }
            String afterClick = innerTextQuietly(article, browserConfig);
            if (afterClick.equals(beforeClick)) {
                LOGGER.debug("Clicked {} post expand control(s), but the post text did not change.", clickedCount);
                return;
            } else {
                LOGGER.info("Expanded post by clicking {} more control(s).", clickedCount);
            }
        }
    }

    private static int clickPostExpandButtons(
            Page page,
            Locator article,
            AppConfig.BrowserConfig browserConfig
    ) {
        int clickedCount = clickPostExpandButtonsWithDom(article);
        if (clickedCount > 0) {
            page.waitForTimeout(browserConfig.expandSettleMillis());
        }
        return clickedCount;
    }

    private static int clickPostExpandButtonsWithDom(Locator article) {
        try {
            Object result = article.evaluate("""
                    article => {
                      const isVisible = element => {
                        const style = window.getComputedStyle(element);
                        const rect = element.getBoundingClientRect();
                        return style.display !== 'none'
                          && style.visibility !== 'hidden'
                          && rect.width > 0
                          && rect.height > 0;
                      };
                      const normalize = text => String(text || '').replace(/\\s+/g, ' ').trim();
                      const ownLabelFor = element => [
                        [...element.childNodes]
                          .filter(node => node.nodeType === Node.TEXT_NODE)
                          .map(node => node.textContent)
                          .join(' '),
                        element.getAttribute('aria-label'),
                        element.getAttribute('title')
                      ].filter(Boolean).join(' ');
                      const isExpandLabel = label => {
                        const normalized = normalize(label)
                          .toLowerCase()
                          .replace(/\\u2026/g, '.')
                          .replace(/\\s+/g, ' ')
                          .trim();
                        return /^(?:\\.{2,}\\s*)?more(?:\\s*\\.{2,})?$/.test(normalized)
                          || normalized === 'see more'
                          || normalized === 'show more'
                          || normalized.includes('see more')
                          || normalized.includes('show more');
                      };
                      const isBlocked = label => /\\b(comment|comments|reply|repost|reposts|send|share)\\b/i.test(label);
                      const closestClickable = element => element.closest('button, [role="button"], a, [onclick], [tabindex]')
                        || element.closest('[class*="show-more"], [class*="see-more"], [class*="inline-show-more"]')
                        || element;
                      const clickElement = element => {
                        const target = closestClickable(element);
                        if (!target || !isVisible(target)) {
                          return false;
                        }
                        target.scrollIntoView({ block: 'center', inline: 'nearest' });
                        const rect = target.getBoundingClientRect();
                        const options = {
                          bubbles: true,
                          cancelable: true,
                          view: window,
                          clientX: rect.left + rect.width / 2,
                          clientY: rect.top + rect.height / 2
                        };
                        target.dispatchEvent(new MouseEvent('mouseover', options));
                        target.dispatchEvent(new MouseEvent('mousedown', options));
                        target.dispatchEvent(new MouseEvent('mouseup', options));
                        target.click();
                        return true;
                      };
                      const candidates = [...article.querySelectorAll('*')];
                      let clicked = 0;
                      const clickedTargets = new Set();
                      for (const candidate of candidates) {
                        const label = ownLabelFor(candidate);
                        if (!label || isBlocked(label) || !isExpandLabel(label) || !isVisible(candidate)) {
                          continue;
                        }
                        const target = closestClickable(candidate);
                        if (target && !clickedTargets.has(target) && clickElement(candidate)) {
                          clickedTargets.add(target);
                          clicked += 1;
                        }
                      }
                      const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT);
                      let node;
                      while ((node = walker.nextNode())) {
                        const label = normalize(node.textContent);
                        if (!label || isBlocked(label) || !isExpandLabel(label)) {
                          continue;
                        }
                        const parent = node.parentElement;
                        if (!parent || !isVisible(parent)) {
                          continue;
                        }
                        const target = closestClickable(parent);
                        if (target && !clickedTargets.has(target) && clickElement(parent)) {
                          clickedTargets.add(target);
                          clicked += 1;
                        }
                      }
                      return clicked;
                    }
                    """);
            if (result instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception exception) {
            LOGGER.debug("Could not click post expand controls with DOM evaluation.", exception);
        }
        return 0;
    }

    static boolean isPostExpandLabel(String label) {
        String normalizedLabel = normalizeWhitespace(label)
                .toLowerCase(Locale.ROOT)
                .replace("\u2026", "...")
                .trim();
        return Pattern.matches("^(?:\\.{2,}\\s*)?more(?:\\s*\\.{2,})?$", normalizedLabel)
                || normalizedLabel.equals("see more")
                || normalizedLabel.equals("show more")
                || normalizedLabel.contains("see more")
                || normalizedLabel.contains("show more");
    }

    private static String innerTextQuietly(Locator locator, AppConfig.BrowserConfig browserConfig) {
        try {
            return normalizeWhitespace(locator.innerText(
                    new Locator.InnerTextOptions().setTimeout(browserConfig.operationTimeoutMillis())
            ));
        } catch (Exception exception) {
            return "";
        }
    }

    private static String articleFingerprint(Locator article, AppConfig.BrowserConfig browserConfig) {
        for (String attributeName : List.of("data-urn", "data-id", "data-view-name", "id")) {
            try {
                String value = article.getAttribute(
                        attributeName,
                        new Locator.GetAttributeOptions().setTimeout(browserConfig.operationTimeoutMillis())
                );
                if (value != null && !value.isBlank()) {
                    return attributeName + ":" + value;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            String text = EmailExtractor.normalizeWhitespace(
                    article.innerText(new Locator.InnerTextOptions()
                            .setTimeout(browserConfig.operationTimeoutMillis()))
            );
            return text.substring(0, Math.min(250, text.length()));
        } catch (Exception exception) {
            return "";
        }
    }

    private static Locator locatePostCards(Page page) {
        String bestSelector = "article";
        int bestCount = -1;
        for (String selector : List.of(
                "[role='listitem']:has-text('Feed post')",
                "[role='listitem']",
                "article"
        )) {
            try {
                int count = page.locator(selector).count();
                if (count > bestCount) {
                    bestSelector = selector;
                    bestCount = count;
                }
            } catch (Exception ignored) {
            }
        }
        return page.locator(bestSelector);
    }

    private static void navigate(Page page, String url) {
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    private static String firstUsefulSentence(String text) {
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            String candidate = sentence.strip().replaceAll("^[\\s\\-|]+|[\\s\\-|]+$", "");
            if (candidate.length() >= 20) {
                return candidate;
            }
        }
        return "";
    }
}
