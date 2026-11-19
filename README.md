# AI Duo

Android starter app for a two-model team chat: ChatGPT drafts, Gemini critiques/adds, and ChatGPT synthesizes a joint answer.

## Build APK in GitHub Actions

1. Open the **Actions** tab.
2. Select **Build AI Duo APK** and tap **Run workflow** (or wait for the workflow triggered by a push).
3. Open the completed run and download the **AI-Duo-debug-apk** artifact.
4. Extract the ZIP and install `app-debug.apk` on your Pixel.

## Important setup before using the app

The Android app calls a backend endpoint. Set `API_BASE_URL` in `android/app/build.gradle.kts` to the public HTTPS URL of your deployed backend before building for the phone. The sample backend is in `backend/`.

Keep API keys on the backend, never in the APK. OpenAI API usage and Gemini API usage are billed separately from ChatGPT subscriptions.

## Backend

Requires Node.js 20+. Set `OPENAI_API_KEY` and `GEMINI_API_KEY` as environment variables, then run:

```bash
cd backend
npm install
npm start
```

This starter is not production-hardened: add authentication, rate limiting, HTTPS, privacy policy, retention/deletion controls, and secure deployment before public use. Voice input uses Android's installed speech recognition service; speech output uses Android TextToSpeech.
