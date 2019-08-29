<p align="center">
    <img src="https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png" alt="Corda" width="500">
</p>

# Confidential Identities CorDapp

## Reminder

This project is open source under an Apache 2.0 licence. That means you
can submit PRs to fix bugs and add new features if they are not currently
available.

## How does the new Confidential Identities CorDapp differ from the existing confidential identities?

In this version of confidential identities we do not store X.509 certificates for confidential identities. This 
significantly reduces the memory overhead for node operators that may want to host multiple _accounts_ and anonymise 
transaction participation. 

Instead, we still store a mapping between a newly generated `PublicKey` for a confidential identity and a `CordaX500Name`. 

This CorDapp contains three flows which can be used to generate, share and synchronise data required for use of confidential
identities in an application. 

### Build confidential identities against Corda branch

In order to use the CorDapp you will need to build against a specific Corda branch until the required changes to the 
`IdentityService` will be released in the `4.3` version. First, clone the Corda repo
and checkout the `release/os/4.3` branch with the following commands:

    git clone https://github.com/corda/corda
    git fetch
    git checkout origin release/os/4.3

Navigate to the `constants.properties` file in the root directory and set the following flag:

    cordaVersion=4.3-SNAPSHOT
   
Then run a `./gradlew clean install` from the root directory. This will be the `cordaVersion` you will need to build the 
`confidential identities` CorDapp against. 

### Adding confidential identities dependencies to an existing CorDapp

First, add a variable for the confidential identities version you wish to use and set the corda version that should've been 
installed locally:

    buildscript {
        ext {
            corda_release_version = '4.3-SNAPSHOT'
            confidential_id_release_version = '1.0-SNAPSHOT'
            confidential_id_release_group = 'com.r3.corda.lib.ci'
        }
    }

Second, you must add the confidential identities development artifactory repository to the
list of repositories for your project:

    repositories {
        maven { url 'http://ci-artifactory.corda.r3cev.com/artifactory/corda-lib-dev' }
    }

Now, you can add the confidential identities dependencies to the `dependencies` block
in each module of your CorDapp. In your workflow `build.gradle` add:

    cordaCompile "$confidential_id_release_group:workflows:$confidential_id_release_version"

If you want to use the `deployNodes` task, you will need to add the
following dependency to your root `build.gradle` file:

    cordapp "$confidential_id_release_group:ci-workflows:$confidential_id_release_version"

These should also be added to the `deployNodes` task with the following syntax:

    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp("$confidential_id_release_group:ci-workflows:$confidential_id_release_version")
    }

## Flows 

#### Challenge Response 

We define the `ChallengeResponse` type alias of `SecureHash.SHA256` to be used to guard against a possible replay attack. 

### RequestKeyFlow

This flow can be used to register a mapping in the `IdentityService` between the `PublicKey` and the `CordaX500Name` for 
a confidential identity. This mapping is required for the node operator to be able to resolve the confidential identity. 

The initiating node will generate a `ChallengeResponse` that is sent to the counter-party. The counter-party creates a
new key pair for an external ID given by a `UUID`. Alternatively, a node can provide the owning key for 
confidential identity that will be registered in the `IdentityService`. The counter-party will generate an additional ``ChallengeResponse``
parameter which is then concatenated with the first and signed over to prevent signing over a malicious transaction. 

After node requests a new confidential key, the counter-party sends back the `SignedKeyForAccount` object that 
wraps the confidential identity `PublicKey` and a serialized and signed version of the `ChallengeResponse`.  The signing key 
and challenge response are verified before registering the mapping in the `IdentityService`.

### SyncKeyMappingsFlow

This flow should be used when a node wishes to synchronise the `PublicKey` to `CordaX500Name` mapping of a confidential 
identity with another node. This confidential participants of a transaction can be extracted from a given `WireTransaction` 
if this is passed as a parameter to the flow. Alternatively, the node can bypass the need for a transaction and accept a
list of `AbstractParty` that it wishes to synchronize with another node. 


### Security protocol

<p align="center">
<<a href="https://ibb.co/bN1ndpj"><img src="https://i.ibb.co/nQrSkhq/ci-flow-security-protocol.png" alt="ci-flow-security-protocol" border="0"></a>
</p>

