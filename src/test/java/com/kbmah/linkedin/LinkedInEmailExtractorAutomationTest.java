package com.kbmah.linkedin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedInEmailExtractorAutomationTest {
    private static Playwright playwright;
    private static Browser browser;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void launchHeadlessBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeHeadlessBrowser() {
        try {
            if (browser != null) {
                browser.close();
            }
        } finally {
            if (playwright != null) {
                playwright.close();
            }
        }
    }

    @Test
    void browserAutomationExtractsEmailIdsAndWritesWorkbook() throws Exception {
        try (Page page = browser.newPage()) {
            page.setContent("""
                    <main>
                      <article role="listitem" data-urn="urn:post:1">
                        Feed post Hiring Python remote developers today.
                        Please contact john123@example.com or recruiter@test.org for details.
                        Like Comment Repost Send
                      </article>
                      <article role="listitem" data-urn="urn:post:2">
                        Feed post Another remote Python opening.
                        Contact JOHN123@example.com and mary-jane99@example.net.
                        Like Comment Repost Send
                      </article>
                    </main>
                    """);

            Locator articles = page.locator("[role='listitem']");
            List<Lead> leads = new ArrayList<>();
            for (int idx = 0; idx < articles.count(); idx++) {
                leads.addAll(EmailExtractor.extractLeads(
                        articles.nth(idx).innerText(),
                        "Python remote hiring"
                ));
            }

            Path output = tempDir.resolve("python-automation-test.xlsx");
            ExcelLeadRepository.save(leads, output);

            try (InputStream inputStream = Files.newInputStream(output);
                 Workbook workbook = WorkbookFactory.create(inputStream)) {
                Sheet sheet = workbook.getSheetAt(0);
                assertEquals(3, sheet.getLastRowNum());
                assertEquals("john123@example.com", sheet.getRow(1).getCell(1).getStringCellValue());
                assertEquals("John", sheet.getRow(1).getCell(2).getStringCellValue());
                assertEquals("recruiter@test.org", sheet.getRow(2).getCell(1).getStringCellValue());
                assertEquals("Recruiter", sheet.getRow(2).getCell(2).getStringCellValue());
                assertEquals("mary-jane99@example.net", sheet.getRow(3).getCell(1).getStringCellValue());
                assertEquals("Mary", sheet.getRow(3).getCell(2).getStringCellValue());
            }
        }
    }

    @Test
    void browserAutomationExpandsMoreButtonInMiddleOfPostBeforeExtractingEmail() throws Exception {
        try (Page page = browser.newPage()) {
            page.setContent("""
                    <main>
                      <article role="listitem" data-urn="urn:post:more-middle">
                        Feed post Hiring Python remote developers today.
                        <button type="button" onclick="
                          document.querySelector('[data-hidden-email]').style.display='inline';
                          this.remove();
                        ">... more</button>
                        <span data-hidden-email style="display:none">
                          Please contact middle-post@example.com for details.
                        </span>
                        Like Comment Repost Send
                      </article>
                    </main>
                    """);

            Locator article = page.locator("[role='listitem']").first();
            EmailExtractor.expandPostContent(page, article);
            List<Lead> leads = EmailExtractor.extractLeads(article.innerText(), "Python remote hiring");

            assertEquals(1, leads.size());
            assertEquals("middle-post@example.com", leads.get(0).email());
        }
    }

    @Test
    void browserAutomationClicksDomMoreControlsBeforeExtractingEmail() throws Exception {
        try (Page page = browser.newPage()) {
            page.setContent("""
                    <main>
                      <article role="listitem" data-urn="urn:post:dom-more">
                        Hiring Python remote developers today.
                        <span class="feed-shared-inline-show-more-text" onclick="
                          document.querySelector('[data-dom-hidden-email]').style.display='inline';
                          this.remove();
                        ">more ...</span>
                        <span data-dom-hidden-email style="display:none">
                          Please contact dom-more@example.com for details.
                        </span>
                        Like Comment Repost Send
                      </article>
                    </main>
                    """);

            Locator article = page.locator("[role='listitem']").first();
            EmailExtractor.expandPostContent(page, article);
            List<Lead> leads = EmailExtractor.extractLeads(article.innerText(), "Python remote hiring");

            assertEquals(1, leads.size());
            assertEquals("dom-more@example.com", leads.get(0).email());
        }
    }

    @Test
    void browserAutomationClicksPlainInlineMoreTextBeforeExtractingEmail() throws Exception {
        try (Page page = browser.newPage()) {
            page.setContent("""
                    <main>
                      <article role="listitem" data-urn="urn:post:plain-more">
                        Hiring Python remote developers today.
                        <span onclick="
                          document.querySelector('[data-plain-hidden-email]').style.display='inline';
                          this.remove();
                        ">more...</span>
                        <span data-plain-hidden-email style="display:none">
                          Please contact plain-more@example.com for details.
                        </span>
                        Like Comment Repost Send
                      </article>
                    </main>
                    """);

            Locator article = page.locator("[role='listitem']").first();
            EmailExtractor.expandPostContent(page, article);
            List<Lead> leads = EmailExtractor.extractLeads(article.innerText(), "Python remote hiring");

            assertEquals(1, leads.size());
            assertEquals("plain-more@example.com", leads.get(0).email());
        }
    }
}
