package com.example.agent.multibrain

import com.example.agent.brain.ActionProposal
import com.example.agent.brain.AgentActionType
import com.example.agent.brain.AgentWorkingMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiBrainOrchestratorTest {
    @Test
    fun defaultsToFullCouncilArchitecture() {
        val orch = MultiBrainOrchestrator(AgentWorkingMemory())
        assertEquals(MultiBrainOrchestrator.Architecture.GROQ_HF_GEMINI, orch.currentArchitecture)
    }

    @Test
    fun actionProposalContractSupportsReplan() {
        val proposal = ActionProposal(AgentActionType.REPLAN, reason = "provider failure")
        assertTrue(proposal.actionType == AgentActionType.REPLAN)
    }
}
