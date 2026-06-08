# LinkedIn Email Extractor

Java automation that searches LinkedIn posts, expands post text, extracts email ids, and appends them to a daily Excel workbook.

## Requirements

- Java 17
- Maven
- A logged-in Chromium Playwright profile, stored at `playwright-profile` by default, or LinkedIn credentials in
  the configured `LINKEDIN_EMAIL` and `LINKEDIN_PASSWORD` environment variables

## Run

Build a standalone jar:

```powershell
mvn clean package
```

Run it as a Java application:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar
```

All runtime settings live in the root-level `application.properties`. By default, the automation writes to
`automation-output\YYYYMMDD.xlsx` and sends pending emails from the daily workbook.
Duplicate emails are skipped within the same daily workbook, and the automation keeps running.
Chromium runs headlessly by default (`browser.headless=true`), so no browser window is displayed. Manual login
is unavailable in this mode; use the saved authenticated profile or the configured credential environment variables.

Configure separate LinkedIn searches as a comma-separated ordered list:

```properties
queries=Hiring Python C2C Remote,Hiring Python W2 Remote
```

Each query runs in its own browser session. After every search finishes and its browser closes, the combined
results are saved to Excel before email sending starts.

Optional arguments:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar --query '"Hiring Python Developer" Remote' --max-scrolls 30 --scroll-pause 2.5 --max-emails 25 --output-dir reports --interval-minutes 5
```

To use a different complete config file:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar --config application.properties
```

For a single test run:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar --run-once --max-emails 5
```

## Test

```powershell
mvn test
```

## Output

Each daily workbook uses these columns:

- `subject`
- `email`
- `firstname`
- `timestamp`
- `Is Email Sent`

Existing rows are preserved. Repeated emails are skipped within the same `YYYYMMDD.xlsx` file.
New rows use `N` for `Is Email Sent`; existing values in that column are not overwritten.

## Email Sending

The main automation sends pending emails automatically after each LinkedIn collection cycle. With the default `interval-minutes=2`, this checks for pending emails every two minutes.
Rows with column E not equal to `Y` are emailed one by one immediately, then column E is updated to `Y` after each successful send.
If Gmail authentication or authorization fails, the sender stops immediately with a failure exit code so credentials can be fixed instead of retrying forever.

Email settings live in `application.properties`:

```properties
email.enabled=true
email.dry-run=false
email.accounts.file=config/python-email-accounts.csv
subject.template.path=profiles/rajhasakket135@gmail.com/subject.txt
subject.variants.path=profiles/rajhasakket135@gmail.com/subject-variants.txt
body.template.path=profiles/rajhasakket135@gmail.com/body.txt
smtp.auth=true
smtp.starttls.enable=true
smtp.host=smtp.gmail.com
smtp.port=587
```

The same file also controls the search query, blocked post keywords, Chromium headless mode and viewport,
LinkedIn URLs, scrolling, browser waits/timeouts, scheduling, output paths, template directories, and
credential environment-variable names. Keep passwords in the configured environment variables rather than
committing them to a properties file.

The default `browser.headless=true` runs Chromium without opening a browser window. Use the `--headless`
argument when overriding a config file that has headless mode disabled.

Put Python-profile Gmail senders and asset paths in `config/python-email-accounts.csv`. Keep each sender's subject,
subject variants, body, and CV together under `profiles/<email>/`:

```csv
id,username,appPassword,ccEmail,attachmentPath,subjectPrefix,subjectPath,subjectVariantsPath,bodyPath
1,rajhasakket135@gmail.com,,kbmahesh72@gmail.com,profiles/rajhasakket135@gmail.com/CV_Raja_Saketh_Garige.docx,,profiles/rajhasakket135@gmail.com/subject.txt,profiles/rajhasakket135@gmail.com/subject-variants.txt,profiles/rajhasakket135@gmail.com/body.txt
```

For 50 Gmail accounts, use numeric ids `1`, `2`, `3`, etc. Pending workbook rows are sent round-robin across all configured accounts. Each row owns its sender Gmail, app password, optional CC email, CV attachment path, subject prefix, subject file, subject variants file, and body file. Leave `ccEmail` blank to send without CC. Use explicit `profiles/<email>/...` paths to keep each profile self-contained. Simple filenames are still resolved under `templates/subjects/` and `templates/body/` for backward compatibility. App passwords can also be supplied as environment variables using `gmail.account-password-env-prefix`, for example `GMAIL_APP_PASSWORD_1`.

Inside each `profiles/<email>/` folder, use `subject.txt`, `subject-variants.txt`, and `body.txt`; keep that
sender's CV in the same folder. Account CSV files are ignored by Git because they may contain Gmail app
passwords. For an environment-variable-only setup, leave `appPassword` blank and set `GMAIL_APP_PASSWORD_1`.
If the configured attachment filename is renamed or missing, the sender automatically uses the first `.docx`
or `.pdf` file found in that same profile folder.

For a small sender list, `email.accounts=1,2` and `email.account.<id>.*` properties are still supported when `email.accounts.file` is blank, including `email.account.<id>.cc.email` and `email.account.<id>.attachment.path`.

The subject variants file is optional. When set, each non-empty line is treated as an approved subject and one is chosen at random for each email. If the file is empty or not configured, the sender uses `subject.template.path`.
The subject and body templates support `${firstname}` from column C. Include each sender's signature directly in that sender's body template.

Standalone email sender command, if you want to send pending emails without running LinkedIn collection:

```powershell
java -cp target\linkedin-email-extractor-1.0.0.jar com.kbmah.linkedin.PendingEmailSenderApplication
```

This standalone sender keeps running. It checks the daily workbook every `interval-minutes` value, which is two minutes in the default `application.properties`.
Use `--run-once` only when you want a single check and then exit.

## Logging

The application logs through SLF4J. Set a log level with:

```powershell
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar target\linkedin-email-extractor-1.0.0.jar
```
