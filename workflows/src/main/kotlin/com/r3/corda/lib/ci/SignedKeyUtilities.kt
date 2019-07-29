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
import java.security.SignatureException
import java.util.*

/**
 * Random number used for authentication of communication between flow sessions.
 */
typealias ChallengeResponse = SecureHash.SHA256

/**
 * Generates a fresh key pair and stores the mapping to the [UUID]. We sign over the [ChallengeResponse] using the new
 * [PublicKey]. The method returns the [SignedKeyForAccount] containing the new [PublicKey] and signed data structure.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param challengeResponseId The random number used to prevent replay attacks
 * @param uuid The external ID to be associated with the new [PublicKey]
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromUUID(serviceHub: ServiceHub, challengeResponseId: ChallengeResponse, uuid: UUID): SignedKeyForAccount {
    require(challengeResponseId.sha256().size == 32)
    val newKey = serviceHub.keyManagementService.freshKey(uuid)
    val newKeySig = serviceHub.keyManagementService.sign(challengeResponseId.serialize().hash.bytes, newKey)
    // Sign the challengeResponse with the newly generated key
    val signedData = SignedData(challengeResponseId.serialize(), newKeySig)
    return SignedKeyForAccount(newKey, signedData)
}

/**
 * Generates a fresh key pair and stores the mapping to the [UUID]. We sign over the [ChallengeResponse] using the new
 * [PublicKey]. The method returns the [SignedKeyForAccount] containing the new [PublicKey] and signed data structure.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param challengeResponseQuestion The random number used to prevent replay attacks
 * @param knownKey The [PublicKey] to sign the challengeResponseId
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromKnownKey(serviceHub: ServiceHub, challengeResponseQuestion: ChallengeResponse, knownKey: PublicKey): SignedKeyForAccount {
    require(challengeResponseQuestion.sha256().size == 32)
    val knownKeySig = serviceHub.keyManagementService.sign(challengeResponseQuestion.serialize().hash.bytes, knownKey)
    // Sign the challengeResponse with the newly generated key
    val signedData = SignedData(challengeResponseQuestion.serialize(), knownKeySig)
    return SignedKeyForAccount(knownKey, signedData)
}

/**
 * Verifies the signature on the used to sign the [ChallengeResponse].
 */
@CordaInternal
@VisibleForTesting
fun verifySignedChallengeResponseSignature(signedKeyForAccount: SignedKeyForAccount) {
    try {
        signedKeyForAccount.signedChallengeResponse.sig.verify(signedKeyForAccount.signedChallengeResponse.raw.hash.bytes)
    } catch (ex: SignatureException) {
        throw SignatureException("The signature on the object does not match that of the expected public key signature", ex)
    }
}

/**
 * Object that holds parameters that drive the behaviour of flows that consume it. The [UUID] can be provided to generate a
 * new [PublicKey] to be associated with the external ID. A known [PublicKey] can be provided to instruct the node to register
 * a mapping between that public key and the node party.
 *
 * @param _challengeResponseQuestion Arbitrary number that can only be used once in a cryptographic communication
 * @param _uuid The external ID for a new key to be mapped to
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
@CordaSerializable
class RequestKeyForAccount
private constructor(private val _challengeResponseQuestion: ChallengeResponse, private val _uuid: UUID?, val knownKey: PublicKey?) {
    constructor(challengeResponseQuestion: ChallengeResponse, knownKey: PublicKey) : this(challengeResponseQuestion, null, knownKey)
    constructor(challengeResponseQuestion: ChallengeResponse, uuid: UUID) : this(challengeResponseQuestion, uuid, null)

    val uuid: UUID?
        get() = _uuid

    val challengeResponseQuestion: ChallengeResponse
        get() = _challengeResponseQuestion
}

/**
 * Object that holds a [PublicKey] and the serialized and signed [ChallengeResponse].
 *
 * @param publicKey The public key that was used to generate the signedChallengeResponse
 * @param signedChallengeResponse The serialized and signed [ChallengeResponse]
 */
@CordaSerializable
data class SignedKeyForAccount(val publicKey: PublicKey, val signedChallengeResponse: SignedData<ChallengeResponse>) {
}

