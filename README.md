# Alfred

A private AI assistant for your messages that runs entirely on your Android phone.

Alfred reads incoming WhatsApp and SMS notifications and, using an on-device LLM (Qwen2.5-1.5B via MediaPipe), decides what to do: reply, add a calendar event, set a reminder, remember a fact, mute spam, or brief you about something it can't handle. Nothing is uploaded.

Alfred works for you, not the people messaging you. It signs every message it sends as your assistant, never promises things it can't do, and asks before anything that speaks for you.

## Customisation

- **Skills**: each action is Automatic, Ask first, or Never.
- **Circles**: Family, Friends, Work and your own, each with a tone, a reply language and how freely Alfred acts.
- **Message types and group chats**: personal, groups, businesses, unknown numbers. Group chats default to "only when I'm mentioned", checked before the AI runs.
- **Rules**: plain-language instructions the model follows within the enabled skills.
- **Memory and places**: facts Alfred may share, and saved places for location-aware reminders. Location is never shared with anyone.

Every action shows why it happened, or which setting held it back.

## Build and install

Requirements: JDK 17, Android SDK 36, an arm64 Android phone with USB debugging.

```bash
# Model (1.6 GB, not in the repo)
mkdir -p models && curl -L -o models/model-4k.task \
  https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task

./gradlew assembleDebug
bash install.sh            # installs the app and pushes the model
bash install.sh --skip-model
```

`install.sh` pushes `models/model.task`; for the 4k-context model push `models/model-4k.task` to `/data/local/tmp/llm/model-4k.task`. The app prefers it when present.
