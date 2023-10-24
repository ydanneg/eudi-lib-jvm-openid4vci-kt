/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.openid4vci.internal.credentialoffer

import eu.europa.ec.eudi.openid4vci.*
import eu.europa.ec.eudi.openid4vci.CredentialSupported.MsoMdoc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URL
import java.time.Duration

/**
 * A default implementation for [CredentialOfferRequestResolver].
 */
internal class DefaultCredentialOfferRequestResolver(
    private val ioCoroutineDispatcher: CoroutineDispatcher,
    private val httpGet: HttpGet<String>,
) : CredentialOfferRequestResolver {

    private val credentialIssuerMetadataResolver = CredentialIssuerMetadataResolver(ioCoroutineDispatcher, httpGet)
    private val authorizationServerMetadataResolver =
        AuthorizationServerMetadataResolver(ioCoroutineDispatcher, httpGet)

    override suspend fun resolve(request: CredentialOfferRequest): Result<CredentialOffer> =
        runCatching {
            val credentialOfferRequestObjectString: String = when (request) {
                is CredentialOfferRequest.PassByValue -> request.value
                is CredentialOfferRequest.PassByReference ->
                    withContext(ioCoroutineDispatcher + CoroutineName("credential-offer-request-object")) {
                        httpGet.get(request.value.value.toURL()).getOrElse {
                            CredentialOfferRequestError.UnableToFetchCredentialOffer(it).raise()
                        }
                    }
            }
            val credentialOfferRequestObject = runCatching {
                Json.decodeFromString<CredentialOfferRequestObject>(credentialOfferRequestObjectString)
            }.getOrElse { CredentialOfferRequestError.NonParseableCredentialOffer(it).raise() }

            val credentialIssuerId = CredentialIssuerId(credentialOfferRequestObject.credentialIssuerIdentifier)
                .getOrElse { CredentialOfferRequestValidationError.InvalidCredentialIssuerId(it).raise() }

            val credentialIssuerMetadata = credentialIssuerMetadataResolver.resolve(credentialIssuerId)
                .getOrElse { CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata(it).raise() }

            val authorizationServerMetadata =
                authorizationServerMetadataResolver.resolve(credentialIssuerMetadata.authorizationServer)
                    .getOrElse { CredentialOfferRequestError.UnableToResolveAuthorizationServerMetadata(it).raise() }

            val credentials = runCatching {
                credentialOfferRequestObject.credentials
                    .map { it.toOfferedCredential(credentialIssuerMetadata) }
                    .also {
                        require(it.isNotEmpty()) { "credentials are required" }
                    }
            }.getOrElse { CredentialOfferRequestValidationError.InvalidCredentials(it).raise() }

            val grants = runCatching {
                credentialOfferRequestObject.grants?.toGrants()
            }.getOrElse { CredentialOfferRequestValidationError.InvalidGrants(it).raise() }

            CredentialOffer(
                credentialIssuerId,
                credentialIssuerMetadata,
                authorizationServerMetadata,
                credentials,
                grants,
            )
        }

    companion object {

        /**
         * Tries to parse a [GrantsObject] to a [Grants] instance.
         */
        private fun GrantsObject.toGrants(): Grants? {
            val maybeAuthorizationCodeGrant =
                authorizationCode?.let { Grants.AuthorizationCode(it.issuerState) }
            val maybePreAuthorizedCodeGrant =
                preAuthorizedCode?.let {
                    Grants.PreAuthorizedCode(
                        it.preAuthorizedCode,
                        it.pinRequired ?: false,
                        it.interval?.let { interval -> Duration.ofSeconds(interval) } ?: Duration.ofSeconds(5L),
                    )
                }

            return when {
                maybeAuthorizationCodeGrant != null && maybePreAuthorizedCodeGrant != null -> Grants.Both(
                    maybeAuthorizationCodeGrant,
                    maybePreAuthorizedCodeGrant,
                )

                maybeAuthorizationCodeGrant == null && maybePreAuthorizedCodeGrant == null -> null
                maybeAuthorizationCodeGrant != null -> maybeAuthorizationCodeGrant
                else -> maybePreAuthorizedCodeGrant
            }
        }

        /**
         * Tries to parse a [JsonElement] as an [CredentialMetadata].
         */
        private fun JsonElement.toOfferedCredential(metadata: CredentialIssuerMetadata): CredentialMetadata =
            if (this is JsonPrimitive && isString) {
                metadata.getOfferedCredentialByScope(content)
            } else if (this is JsonObject) {
                toOfferedCredential(metadata)
            } else {
                throw IllegalArgumentException("Invalid JsonElement for Credential. Found '$javaClass'")
            }

        /**
         * Gets an [CredentialMetadata] by its scope.
         */
        private fun CredentialIssuerMetadata.getOfferedCredentialByScope(scope: String): CredentialMetadata =
            credentialsSupported
                .firstOrNull { it.scope == scope }
                ?.let {
                    CredentialMetadata.ByScope(Scope.of(scope))
                }
                ?: throw IllegalArgumentException("Unknown scope '$scope")

        /**
         * Converts this [JsonObject] to a [CredentialMetadata.ProfileSpecific] object.
         *
         * The resulting [CredentialMetadata.ProfileSpecific] must be supported by the Credential Issuer and be present in its [CredentialIssuerMetadata].
         */
        private fun JsonObject.toOfferedCredential(metadata: CredentialIssuerMetadata): CredentialMetadata {
            val format =
                getOrDefault("format", JsonNull)
                    .let {
                        if (it is JsonPrimitive && it.isString) {
                            it.content
                        } else {
                            throw IllegalArgumentException("Invalid 'format'")
                        }
                    }

            fun CredentialIssuerMetadata.getMatchingMsoMdocCredential(): CredentialMetadata.MsoMdoc {
                val docType = Json.decodeFromJsonElement<MsoMdocCredentialObject>(
                    this@toOfferedCredential,
                ).docType

                fun fail(): Nothing =
                    throw IllegalArgumentException("Unsupported MsoMdocCredential with format '$format' and docType '$docType'")

                return credentialsSupported
                    .firstOrNull {
                        it is CredentialSupported.MsoMdoc &&
                            it.docType == docType
                    }
                    ?.let {
                        CredentialMetadata.MsoMdoc(docType, (it as MsoMdoc).scope)
                    }
                    ?: fail()
            }

            fun CredentialIssuerMetadata.getMatchingSignedJwtCredential(): CredentialMetadata.SignedJwt {
                val credentialDefinition = Json.decodeFromJsonElement<W3CVerifiableCredentialCredentialObject>(
                    this@toOfferedCredential,
                ).credentialDefinition

                fun fail(): Nothing =
                    throw IllegalArgumentException(
                        "Unsupported W3CVerifiableCredential with format '$format' and credentialDefinition '$credentialDefinition'",
                    )

                return credentialsSupported
                    .firstOrNull {
                        it is CredentialSupported.SignedJwt &&
                            it.credentialDefinition.type == credentialDefinition.type
                    }
                    ?.let {
                        CredentialMetadata.SignedJwt(
                            CredentialDefinitionMetadata.NonLd(
                                type = credentialDefinition.type,
                            ),
                            it.scope,
                        )
                    }
                    ?: fail()
            }

            fun CredentialIssuerMetadata.getMatchingJsonLdSignedJwtCredential(): CredentialMetadata.JsonLdSignedJwt {
                val credentialDefinition = Json.decodeFromJsonElement<W3CVerifiableCredentialCredentialObject>(
                    this@toOfferedCredential,
                ).credentialDefinition

                fun fail(): Nothing =
                    throw IllegalArgumentException(
                        "Unsupported W3CVerifiableCredential with format '$format' and credentialDefinition '$credentialDefinition'",
                    )

                return credentialsSupported
                    .firstOrNull {
                        it is CredentialSupported.JsonLdSignedJwt &&
                            it.credentialDefinition.type == credentialDefinition.type &&
                            it.credentialDefinition.context.map { it.toString() } == credentialDefinition.context
                    }
                    ?.let {
                        CredentialMetadata.JsonLdSignedJwt(
                            CredentialDefinitionMetadata.LdSpecific(
                                type = credentialDefinition.type,
                                content = credentialDefinition.context?.map { URL(it) } ?: throw IllegalArgumentException("Unparsable"),
                            ),
                            it.scope,
                        )
                    }
                    ?: fail()
            }

            fun CredentialIssuerMetadata.getMatchingJsonLdDataIntegrity(): CredentialMetadata.JsonLdDataIntegrity {
                val credentialDefinition = Json.decodeFromJsonElement<W3CVerifiableCredentialCredentialObject>(
                    this@toOfferedCredential,
                ).credentialDefinition

                fun fail(): Nothing =
                    throw IllegalArgumentException(
                        "Unsupported W3CVerifiableCredential with format '$format' and credentialDefinition '$credentialDefinition'",
                    )

                return credentialsSupported
                    .firstOrNull {
                        it is CredentialSupported.JsonLdDataIntegrity &&
                            it.credentialDefinition.type == credentialDefinition.type &&
                            it.credentialDefinition.context.map { it.toString() } == credentialDefinition.context
                    }
                    ?.let {
                        CredentialMetadata.JsonLdDataIntegrity(
                            CredentialDefinitionMetadata.LdSpecific(
                                type = credentialDefinition.type,
                                content = credentialDefinition.context?.map { URL(it) } ?: throw IllegalArgumentException("Unparsable"),
                            ),
                            it.scope,
                        )
                    }
                    ?: fail()
            }

            return when (format) {
                "mso_mdoc" -> metadata.getMatchingMsoMdocCredential()
                "jwt_vc_json" -> metadata.getMatchingSignedJwtCredential()
                "jwt_vc_json-ld" -> metadata.getMatchingJsonLdSignedJwtCredential()
                "ldp_vc" -> metadata.getMatchingJsonLdDataIntegrity()

                else -> throw IllegalArgumentException("Unknown Credential format '$format'")
            }
        }
    }
}
