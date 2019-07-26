package com.r3.corda.lib.ci

import net.corda.core.CordaInternal
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.sha256
import net.corda.core.internal.VisibleForTesting
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.serialize
import java.security.PublicKey
import java.util.*

/**
 * Random number.
 */
typealias ChallengeResponse = SecureHash.SHA256

/**
 * Generates and returns a signed [SignedOwnershipClaim] which contains data on the newly generated [PublicKey] to be associated
 * with the provided external ID.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param uuid The external ID to be associated with the new [PublicKey]
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromUUID(serviceHub: ServiceHub, challengeResponseId: ChallengeResponse, uuid: UUID): SignedKeyForAccount {
    //TODO is this check still required?
    require(challengeResponseId.sha256().size == 32)
    val newKey = serviceHub.keyManagementService.freshKey(uuid)
    val newKeySig = serviceHub.keyManagementService.sign(challengeResponseId.serialize().hash.bytes, newKey)
    // Sign the challengeResponse with the newly generated key
    val signedData = SignedData(challengeResponseId.serialize(), newKeySig)
    return SignedKeyForAccount(newKey, signedData)
}

/**
 * Generates and returns a signed [SignedOwnershipClaim] created against a known [PublicKey] that is provided to the method.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromKnownKey(serviceHub: ServiceHub, challengeResponseId: ChallengeResponse, knownKey: PublicKey): SignedKeyForAccount {
    //TODO is this check still required?
    require(challengeResponseId.sha256().size == 32)
    val knownKeySig = serviceHub.keyManagementService.sign(challengeResponseId.serialize().hash.bytes, knownKey)
    // Sign the challengeResponse with the newly generated key
    val signedData = SignedData(challengeResponseId.serialize(), knownKeySig)
    return SignedKeyForAccount(knownKey, signedData)
}

/**
 * Object that holds parameters that drive the behaviour of flows that consume it. The [UUID] can be provided to generate a
 * new [PublicKey] to be associated with the external ID. A known [PublicKey] can be provided to instruct the node to register
 * a mapping between that public key and the node party.
 *
 * @param _challengeResponse Arbitrary number that can only be used once in a cryptographic communication
 * @param _uuid The external ID for a new key to be mapped to
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
@CordaSerializable
class RequestKeyForAccount
private constructor(private val _challengeResponseId: ChallengeResponse, private val _uuid: UUID?, val knownKey: PublicKey?) {
    constructor(challengeResponseId: ChallengeResponse, knownKey: PublicKey) : this(challengeResponseId, null, knownKey)
    constructor(challengeResponseId: ChallengeResponse, uuid: UUID) : this(challengeResponseId, uuid, null)

    val uuid: UUID?
        get() = _uuid

    val challengeResponseId: ChallengeResponse
        get() = _challengeResponseId
}

@CordaSerializable
data class SignedKeyForAccount(val publicKey: PublicKey, val signedChallengeResponse: SignedData<ChallengeResponse>)

