package com.r3.corda.lib.ci

import net.corda.core.CordaInternal
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.newSecureRandom
import net.corda.core.internal.VisibleForTesting
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.serialize
import net.corda.core.utilities.OpaqueBytes
import java.math.BigInteger
import java.security.PublicKey
import java.util.*

/**
 * Generates and returns a signed [OwnershipClaim] which contains data on the newly generated [PublicKey] to be associated
 * with the provided external ID.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param uuid The external ID to be associated with the new [PublicKey]
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromUUID(serviceHub: ServiceHub, uuid: UUID): SignedData<OwnershipClaim> {
    val nodeParty = serviceHub.myInfo.legalIdentities.first()
    val newKey = serviceHub.keyManagementService.freshKey(uuid)
    val nonce = BigInteger(123, newSecureRandom()).toByte()
    val ownershipClaim = OwnershipClaim(OpaqueBytes.of(nonce), newKey)
    val sig = serviceHub.keyManagementService.sign(ownershipClaim.serialize().bytes, nodeParty.owningKey)
    return SignedData(ownershipClaim.serialize(), sig)
}

/**
 * Generates and returns a signed [OwnershipClaim] created against a known [PublicKey] that is provided to the method.
 *
 * @param serviceHub The [ServiceHub] of the node which requests a new public key
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromKnownKey(serviceHub: ServiceHub, knownKey: PublicKey): SignedData<OwnershipClaim> {
    val nodeParty = serviceHub.myInfo.legalIdentities.first()
    val nonce = BigInteger(123, newSecureRandom()).toByte()
    val ownershipClaim = OwnershipClaim(OpaqueBytes.of(nonce), knownKey)
    val sig = serviceHub.keyManagementService.sign(ownershipClaim.serialize().bytes, nodeParty.owningKey)
    return SignedData(ownershipClaim.serialize(), sig)
}

/**
 * Object that holds parameters that drive the behaviour of flows that consume it. The [UUID] can be provided to generate a
 * new [PublicKey] to be associated with the external ID. A known [PublicKey] can be provided to instruct the node to register
 * a mapping between that public key and the node party.
 *
 * @param uuid The external ID for a new key to be mapped to
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
@CordaSerializable
class CreateKeyForAccount
private constructor(val uuid: UUID?, val knownKey: PublicKey?) {
    constructor(knownKey: PublicKey) : this(null, knownKey)
    constructor(uuid: UUID) : this(uuid, null)
}

/**
 * To be used in conjunction with [SignedData] when using confidential identities. The [PublicKey] represents the owning key of a
 * confidential identity.
 *
 * @param nonce Arbitrary unique number
 * @param key The [PublicKey] to be actioned
 */
@CordaSerializable
data class OwnershipClaim(val nonce: OpaqueBytes, val key: PublicKey)
