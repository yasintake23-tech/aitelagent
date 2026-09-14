package com.example.agent.multibrain

interface ReasoningBrain {
    suspend fun proposePlan(goal: String, context: ScreenContext): List<String>
    suspend fun proposeAction(
        goal: String, 
        context: ScreenContext, 
        history: List<AgentMessage>
    ): AgentMessage
}

interface VisionBrain {
    suspend fun analyzeScreen(
        context: ScreenContext, 
        targetDescription: String? = null
    ): AgentMessage
}

interface AdvisorBrain {
    suspend fun provideSecondOpinion(
        task: String, 
        context: ScreenContext, 
        proposal: AgentMessage
    ): AgentMessage
}
