package com.r3.corda.lib.ci

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.deserialize
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
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestKeyFlowTests {

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
                            TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                            TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                            TestCordapp.findCordapp("com.r3.corda.lib.tokens.money"),
                            TestCordapp.findCordapp("com.r3.corda.lib.ci")
                    ),
                    threadPerNode = true
                )
            )
        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        charlie = charlieNode.info.singleIdentity()
        notary = mockNet.defaultNotaryIdentity

        mockNet.startNodes()

    }

    @After
    fun after() {
        mockNet.stopNodes()
    }

    @Test
    fun `request new key from another party`() {
        // Alice requests that bob generates a new key for an account
        val keyForBob = aliceNode.startFlow(RequestKeyInitiator(bob, UUID.randomUUID())).let{
            it.getOrThrow()
        }
        val bobKey = keyForBob.raw.deserialize().key

        // Bob has the newly generated key as well as the owning key
        val bobKeys = bobNode.services.keyManagementService.keys
        val aliceKeys = aliceNode.services.keyManagementService.keys
        assertThat(bobKeys).hasSize(2)
        assertThat(aliceKeys).hasSize(1)

        assertThat(bobNode.services.keyManagementService.keys).contains(bobKey)

        val resolvedBobParty = aliceNode.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(bobKey))
        assertThat(resolvedBobParty).isEqualTo(bob)
    }

    @Test
    fun `verify a known key with another party`() {
        // Charlie issues then pays some cash to a new confidential identity
        val anonymousParty = charlieNode.startFlow(ConfidentialIdentityInitiator(alice)).let{
            it.getOrThrow()
        }

        val issueTx = charlieNode.startFlow(
                IssueTokens(listOf(1000 of USD issuedBy charlie heldBy AnonymousParty(anonymousParty.owningKey)))
        ).getOrThrow()
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<FungibleToken<TokenType>>().single().holder
        // Verify Bob cannot resolve the CI before we create a signed mapping of the CI key
        assertNull(bobNode.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })
        bobNode.startFlow(RequestKeyInitiator(alice, confidentialIdentity.owningKey)).let {
            it.getOrThrow()
        }

        val expected = charlieNode.transaction {
            charlieNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        val actual = bobNode.transaction {
            bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        assertEquals(expected, actual)
    }
}