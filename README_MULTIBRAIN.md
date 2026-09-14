# Lumina AI — Multi-Brain Autonomous Architecture

## Runtime architecture

### 2-Brain mode: GROQ_HF
For every autonomous physical step:
1. Groq Reasoning proposes one `ActionProposal`.
2. Hugging Face Vision receives the current screenshot and returns structured visual grounding (`found`, candidate X/Y, confidence).
3. Groq receives the grounding observation and produces the final `ActionProposal`.
4. SafetyGuardian is the final gate before AccessibilityService executes anything.

### 3-Brain mode: GROQ_HF_GEMINI (default)
For every autonomous physical step:
1. Groq Reasoning proposes.
2. Hugging Face Vision grounds the current UI from the screenshot.
3. Groq refines the proposal using the Vision observation.
4. Gemini Advisor independently reviews the final proposal.
5. Gemini disagreement, high-risk assessment, or low confidence produces `REPLAN`.
6. SafetyGuardian is still the final physical-action gate.

There is no autonomous fallback from one cloud provider to another. A missing provider key or provider failure stops the council safely and requests replanning/failure handling.

## API keys
Store provider keys independently in the app's CredentialStore:
- `groq` — Reasoning brain
- `huggingface` — Vision brain
- `gemini` — Advisor brain (3-Brain mode only)

Normal conversational chat can continue to use the user's selected single provider. Multi-Brain applies to autonomous device control/exploration only.

## Configuration
Open **Ayarlar → Multi-Brain Otonom Agent** and choose:
- **2 Beyin:** Groq + Hugging Face Vision
- **3 Beyin:** Groq + Hugging Face Vision + Gemini Advisor

The same Settings screen shows whether the required keys are configured.
