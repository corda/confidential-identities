package com.r3.corda.lib.ci

import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*

class ShareKeyFlowTests {
    private lateinit var mockNet: MockNetwork
    private lateinit var aliceNode: StartedMockNode
    private lateinit var bobNode: StartedMockNode
    private lateinit var charlieNode: StartedMockNode
    private lateinit var alice: Party
    private lateinit var bob: Party
    private lateinit var charlie: Party
    private lateinit var notary: Party

    @Before
    fun before() {
        mockNet = MockNetwork(
                MockNetworkParameters(
                        networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                        cordappsForAllNodes = listOf(
                                TestCordapp.findCordapp("com.r3.corda.lib.ci")
                        )
                )
        )
        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        charlie = charlieNode.info.singleIdentity()
        notary = mockNet.defaultNotaryIdentity

        mockNet.runNetwork()

    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `share new key with another party `() {
        // Alice creates a new key and shares it with Bob
        aliceNode.startFlow(ShareKeyInitiator(bob, UUID.randomUUID())).let {
            mockNet.runNetwork()
            it.getOrThrow()
        }

        // Alice has her own key and the newly generated key
        val aliceKeys = aliceNode.services.keyManagementService.keys
        assertThat(aliceKeys).hasSize(2)

        val aliceGeneratedKey = aliceKeys.last()

        // Bob should be able to resolve the generated key as it has been shared with him
        val resolvedParty = bobNode.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(aliceGeneratedKey))
        assertThat(resolvedParty).isEqualTo(alice)
    }
}


