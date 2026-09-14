# Lumina AI - Multi-Brain Runtime Stabilization

This revision makes the autonomous runtime use the shared Multi-Brain AgentBrain for both user tasks and device exploration, captures a fresh AccessibilityService screenshot before Vision decisions, fixes coordinate execution and directional swipes, prevents blank-target first-node selection, disables legacy single-AI visual opener/WhatsApp paths, and adds persistent diagnostic logs visible in Settings > Geçmiş Loglar.

Runtime chain:
Groq reasoning -> HF Vision grounding -> Groq refinement -> Gemini Advisor (3-brain mode) -> SafetyGuardian -> Accessibility physical action -> verification.

2-brain mode omits Gemini Advisor. Normal chat remains isolated from the autonomous pipeline.
