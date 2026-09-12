# ⚡ Jarvis — Real-Time Android AI Voice Assistant

Jarvis is a high-performance, hands-free voice assistant engineered for Android, integrating **Gemini 1.5 Flash** for natural conversational intelligence alongside deep operating-system level automation via Android Accessibility Services.

Built with performance, security, and human-like natural conversation in mind, Jarvis automates device control tasks while maintaining a warm, contextual persona in Urdu, Roman Urdu, and English.

---

## 🚀 Key Features

* **LLM-Powered Companion Brain**: Integrated with Google's Gemini 1.5 Flash API for zero-lag, context-aware, witty, and emotionally resonant conversational interactions.
* **Hands-Free WhatsApp Automation**:
  * Direct WhatsApp voice call dispatching without intermediate phone dialers.
  * Accessibility-driven chat messaging with auto-target selection and instant payload delivery.
* **Instant Device Lock**: Dedicated OS-level accessibility triggers to lock device displays within ~200ms upon voice commands.
* **Clean Security Architecture**: Fully decoupled API credential management leveraging Gradle `local.properties` and build-time `BuildConfig` injection, keeping repositories 100% compliant with automated Secret Scanning.
* **Optimized TTS Engine**: Tuned neural text-to-speech cadence and speech rates for authentic, human-like voice responses.

---

## 🛠️ Architecture & Tech Stack

* **Platform**: Android SDK (Targeting modern Android APIs)
* **Language**: Kotlin, Coroutines
* **AI Core**: Google Generative AI (Gemini 1.5 Flash REST API)
* **OS Automation**: Android Accessibility Services & System Alert Overlay
* **Networking & Parsing**: OkHttp / Retrofit, Gson
* **Build System**: Gradle Kotlin DSL (`build.gradle.kts`) with `BuildConfig` dynamic fields

---

## 📂 Project Structure

```text
app/src/main/java/com/aura/assistant/
├── AuraAIEngine.kt              # Intent evaluation and query dispatcher
├── JarvisOnlineEngine.kt         # Gemini 1.5 Flash API client & streaming engine
├── AuraAccessibilityService.kt   # WhatsApp dispatch & lock-screen automation
├── AuraBackgroundService.kt      # Continuous voice capture & HUD management
├── MainActivity.kt               # App configuration & permissions dashboard
└── AuraVoiceDialogActivity.kt    # Dynamic visual listening HUD
