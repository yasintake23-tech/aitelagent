# Lumina AI – Stable Multi-Brain Runtime Fix

This build addresses runtime stability problems observed on-device:

- A single serialized/throttled screenshot pipeline prevents Android screenshot error code 3 (requests too close together).
- Multi-Brain coordination is serialized so only one council decision is active at a time.
- Multi-Brain task initialization no longer burns an extra planner LLM call before the first action.
- Provider/screenshot planning failures no longer trigger an unbounded replan storm; retries are bounded and paced.
- Main autonomous actions are paced between steps to let Accessibility events and UI animations settle.
- Voice recognition remains active while an autonomous task is running.
- While the agent is busy, every new voice/text command is ignored except an explicit stop/cancel command.
- Back-to-back recognition results cannot start two autonomous tasks because the agent-busy state is claimed synchronously.
- The previous Multi-Brain architecture and persistent diagnostic logs remain enabled.

Android's AccessibilityService screenshot API reports error code 3 when too little time has elapsed since the previous screenshot, so all screenshot callers now use the centralized gate.
