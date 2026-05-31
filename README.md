# LinkedIn Email Extractor

Java automation that searches LinkedIn posts, expands post text, extracts email ids, and appends them to a daily Excel workbook.

## Requirements

- Java 17
- Maven
- A logged-in Chromium Playwright profile, stored at `playwright-profile` by default

## Run

Build a standalone jar:

```powershell
mvn clean package
```

Run it as a Java application:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar
```

By default, the automation reads `application.properties`, runs every 10 seconds, writes to `automation-output\YYYYMMDD.xlsx`, and sends pending emails from the daily workbook.
Duplicate emails are skipped within the same daily workbook, and the automation keeps running.

Optional arguments:

```powershell
java -jar target\linkedin-email-extractor-1.0.0.jar --query "c2c Java" --max-scrolls 30 --scroll-pause 2.5 --max-emails 25 --output-dir reports --interval-minutes 5
```

To use a custom config file:

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

The main automation sends pending emails automatically after each LinkedIn collection cycle. With the default `interval-minutes=0.1666666667`, this checks for pending emails every 10 seconds.
Rows with column E not equal to `Y` are emailed one by one immediately, then column E is updated to `Y` after each successful send.
If Gmail authentication or authorization fails, the sender stops immediately with a failure exit code so credentials can be fixed instead of retrying forever.

Email settings live in `application.properties`:

```properties
email.enabled=true
email.dry-run=false
email.accounts.file=config/email-accounts.csv
subject.template.path=templates/subjects/default.txt
subject.variants.path=
body.template.path=templates/body/default.txt
```

Put Gmail senders and template paths in `config/email-accounts.csv`:

```csv
id,username,appPassword,ccEmail,attachmentPath,subjectPrefix,subjectPath,subjectVariantsPath,bodyPath
1,sender01@gmail.com,app password,cc@example.com,resume/Sender01_Resume.docx,,sender01@gmail.com.txt,sender01@gmail.com.variants.txt,sender01@gmail.com.txt
2,sender02@gmail.com,app password,cc@example.com,resume/Sender02_Resume.docx,GC - ,sender02@gmail.com.txt,sender02@gmail.com.variants.txt,sender02@gmail.com.txt
```

For 50 Gmail accounts, use numeric ids `1`, `2`, `3`, etc. Pending workbook rows are sent round-robin across all configured accounts. Each row owns its sender Gmail, app password, optional CC email, resume attachment path, subject prefix, subject file, subject variants file, and body file. Leave `ccEmail` blank to send without CC. Subject files live under `templates/subjects/`, and body files live under `templates/body/`; if `subjectPath`, `subjectVariantsPath`, or `bodyPath` is just a filename, the app looks in those folders automatically. App passwords can also be supplied as environment variables named `GMAIL_APP_PASSWORD_<ID>`, for example `GMAIL_APP_PASSWORD_1`.

Use Gmail addresses as subject/body filenames, for example `templates/subjects/surya.p2805@gmail.com.txt` and `templates/body/surya.p2805@gmail.com.txt`. To prepend `GC - ` for Surya, set `subjectPrefix` to `GC - ` in that CSV row.

For a small sender list, `email.accounts=1,2` and `email.account.<id>.*` properties are still supported when `email.accounts.file` is blank, including `email.account.<id>.cc.email` and `email.account.<id>.attachment.path`.

The subject variants file is optional. When set, each non-empty line is treated as an approved subject and one is chosen at random for each email. If the file is empty or not configured, the sender uses `subject.template.path`.
The subject and body templates support `${firstname}` from column C. Include each sender's signature directly in that sender's body template.

Standalone email sender command, if you want to send pending emails without running LinkedIn collection:

```powershell
java -cp target\linkedin-email-extractor-1.0.0.jar com.kbmah.linkedin.PendingEmailSenderApplication
```

This standalone sender keeps running. It checks the daily workbook every `interval-minutes` value, which is 10 seconds in the default `application.properties`.
Use `--run-once` only when you want a single check and then exit.

## Logging

The application logs through SLF4J. Set a log level with:

```powershell
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar target\linkedin-email-extractor-1.0.0.jar
```
