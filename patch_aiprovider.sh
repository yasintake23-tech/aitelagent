sed -i 's/return ""/throw IllegalArgumentException("API_KEY_MISSING")/g' app/src/main/java/com/example/ai/AIProviderManager.kt
sed -i 's/""/throw e/g' app/src/main/java/com/example/ai/AIProviderManager.kt
