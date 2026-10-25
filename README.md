# Alfred

A private AI assistant for your messages that runs entirely on your Android phone.

Alfred reads incoming WhatsApp and SMS notifications and, using an on-device LLM (Qwen2.5-1.5B via MediaPipe), decides what to do: reply, add a calendar event, set a reminder, remember a fact, mute spam, or brief you about something it can't handle. Nothing is uploaded.

Alfred works for you, not the people messaging you. It signs every message it sends as your assistant, never promises things it can't do, and asks before anything that speaks for you.

## What it does for you

- **Answer in one tap.** A briefing arrives with quick answers ("Works for me", "Can't make it") and a reply box. Type "yes 8" and Alfred sends "Suchal says 8 works 👍" and books it.
- **Knows your day.** Your status ("In a meeting for 1 hour") and calendar free/busy shape what people hear right away, without revealing event details or where you are.
- **Learns from you.** Your own replies become examples of your voice; after three approved replies to a circle, Alfred offers to handle that circle on its own.
- **Follows up.** Unanswered items get a "Still checking with Suchal" after 30 minutes, and a second nudge to you.
- **Ask Alfred.** "tell Praveen I'll be 10 min late", "what's pending?", "I'm back".
- **Interrupts only when it matters.** Urgent and decisions notify with answer buttons; everything else collects in one quiet FYI summary.

## Install

Download [`apk/Alfred.apk`](apk/Alfred.apk) to an arm64 Android phone (8 GB RAM recommended) and push the model as below.

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
