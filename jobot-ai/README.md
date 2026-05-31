# Jobot AI

React/Vite app for capturing job-seeker details for LinkedIn recruiter email alerts.

## Run

```powershell
npm.cmd install
npm.cmd run dev
```

`npm.cmd run dev` starts both the React app and the local subscription API.

Saved data is written to:

- `data/subscriptions.csv`
- `data/resumes/<subscriber-email>.<resume-extension>`

## Build

```powershell
npm.cmd run build
```

The app has three tabs:

- Tool overview
- Subscription configuration form
- Contact details
