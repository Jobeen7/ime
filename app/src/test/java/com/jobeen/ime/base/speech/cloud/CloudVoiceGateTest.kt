package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** 路由门：条件判定顺序与全通过走云端。 */
class CloudVoiceGateTest {

    private fun decide(
        optIn: Boolean = true,
        preferred: String? = "p1",
        installed: Boolean = true,
        budget: Boolean = false,
        secretsMissing: Boolean = false,
        network: Boolean = true,
    ) = CloudVoiceGate.decide(optIn, preferred, installed, budget, secretsMissing, network)

    @Test
    fun gate_allConditionsPass_goesCloud() {
        assertEquals(CloudVoiceGateResult.Cloud, decide())
    }

    @Test
    fun gate_firstFailingConditionWins_inOrder() {
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.NOT_OPTED_IN),
            decide(optIn = false, preferred = null, installed = false),
        )
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.NO_PREFERRED),
            decide(preferred = null, installed = false),
        )
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.PLUGIN_MISSING),
            decide(installed = false, budget = true),
        )
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.BUDGET_DISABLED),
            decide(budget = true, secretsMissing = true),
        )
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.SECRET_MISSING),
            decide(secretsMissing = true, network = false),
        )
        assertEquals(
            CloudVoiceGateResult.Offline(CloudFailure.NETWORK_UNAVAILABLE),
            decide(network = false),
        )
    }
}
