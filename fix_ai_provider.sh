sed -i 's/profile?.customApiKey ?: throw e/profile?.customApiKey ?: ""/g' app/src/main/java/com/example/ai/AIProviderManager.kt
sed -i 's/replace(":", throw e)/replace(":", "")/g' app/src/main/java/com/example/ai/AIProviderManager.kt
