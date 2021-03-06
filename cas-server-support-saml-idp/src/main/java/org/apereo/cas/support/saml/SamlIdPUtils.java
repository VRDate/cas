package org.apereo.cas.support.saml;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.saml.services.SamlRegisteredService;
import org.apereo.cas.support.saml.services.idp.metadata.SamlRegisteredServiceServiceProviderMetadataFacade;
import org.apereo.cas.support.saml.services.idp.metadata.cache.SamlRegisteredServiceCachingMetadataResolver;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.messaging.context.SAMLEndpointContext;
import org.opensaml.saml.common.messaging.context.SAMLPeerEntityContext;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.criterion.BindingCriterion;
import org.opensaml.saml.criterion.EntityRoleCriterion;
import org.opensaml.saml.metadata.resolver.ChainingMetadataResolver;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.Endpoint;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.impl.AssertionConsumerServiceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This is {@link SamlIdPUtils}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public final class SamlIdPUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(SamlIdPUtils.class);

    private SamlIdPUtils() {
    }

    /**
     * Prepare peer entity saml endpoint.
     *
     * @param outboundContext the outbound context
     * @param adaptor         the adaptor
     * @throws SamlException the saml exception
     */
    public static void preparePeerEntitySamlEndpointContext(final MessageContext outboundContext,
                                                            final SamlRegisteredServiceServiceProviderMetadataFacade adaptor)
            throws SamlException {
        final List<AssertionConsumerService> assertionConsumerServices = adaptor.getAssertionConsumerServices();
        if (assertionConsumerServices.isEmpty()) {
            throw new SamlException(SamlException.CODE, "No assertion consumer service could be found for entity " + adaptor.getEntityId());
        }

        final SAMLPeerEntityContext peerEntityContext = outboundContext.getSubcontext(SAMLPeerEntityContext.class, true);
        if (peerEntityContext == null) {
            throw new SamlException(SamlException.CODE, "SAMLPeerEntityContext could not be defined for entity " + adaptor.getEntityId());
        }

        final SAMLEndpointContext endpointContext = peerEntityContext.getSubcontext(SAMLEndpointContext.class, true);
        if (endpointContext == null) {
            throw new SamlException(SamlException.CODE, "SAMLEndpointContext could not be defined for entity " + adaptor.getEntityId());
        }
        final Endpoint endpoint = assertionConsumerServices.get(0);
        if (StringUtils.isBlank(endpoint.getBinding()) || StringUtils.isBlank(endpoint.getLocation())) {
            throw new SamlException(SamlException.CODE, "Assertion consumer service does not define a binding or location for "
                    + adaptor.getEntityId());
        }
        LOGGER.debug("Configured peer entity endpoint to be [{}] with binding [{}]", endpoint.getLocation(), endpoint.getBinding());
        endpointContext.setEndpoint(endpoint);
    }

    /**
     * Gets chaining metadata resolver for all saml services.
     *
     * @param servicesManager the services manager
     * @param entityID        the entity id
     * @param resolver        the resolver
     * @return the chaining metadata resolver for all saml services
     */
    public static MetadataResolver getMetadataResolverForAllSamlServices(final ServicesManager servicesManager,
                                         final String entityID, final SamlRegisteredServiceCachingMetadataResolver resolver) {
        try {
            final Predicate p = Predicates.instanceOf(SamlRegisteredService.class);
            final Collection<RegisteredService> registeredServices = servicesManager.findServiceBy(p);
            final List<MetadataResolver> resolvers = new ArrayList<>();
            final ChainingMetadataResolver chainingMetadataResolver = new ChainingMetadataResolver();

            for (final RegisteredService registeredService : registeredServices) {
                final SamlRegisteredService samlRegisteredService = SamlRegisteredService.class.cast(registeredService);

                final SamlRegisteredServiceServiceProviderMetadataFacade adaptor =
                        SamlRegisteredServiceServiceProviderMetadataFacade.get(resolver, samlRegisteredService, entityID);
                resolvers.add(adaptor.getMetadataResolver());
            }
            chainingMetadataResolver.setResolvers(resolvers);
            chainingMetadataResolver.setId(entityID);
            chainingMetadataResolver.initialize();
            return chainingMetadataResolver;
        } catch (final Exception e) {
            throw new RuntimeException(new SamlException(e.getMessage(), e));
        }
    }

    /**
     * Gets assertion consumer service for.
     *
     * @param authnRequest    the authn request
     * @param servicesManager the services manager
     * @param resolver        the resolver
     * @return the assertion consumer service for
     */
    public static AssertionConsumerService getAssertionConsumerServiceFor(final AuthnRequest authnRequest, 
                final ServicesManager servicesManager, final SamlRegisteredServiceCachingMetadataResolver resolver) {
        try {
            final AssertionConsumerService acs = new AssertionConsumerServiceBuilder().buildObject();
            if (authnRequest.getAssertionConsumerServiceIndex() != null) {
                final String issuer = getIssuerFromSamlRequest(authnRequest);
                final MetadataResolver samlResolver = getMetadataResolverForAllSamlServices(servicesManager, issuer, resolver);
                final CriteriaSet criteriaSet = new CriteriaSet();
                criteriaSet.add(new EntityIdCriterion(issuer));
                criteriaSet.add(new EntityRoleCriterion(SPSSODescriptor.DEFAULT_ELEMENT_NAME));
                criteriaSet.add(new BindingCriterion(Lists.newArrayList(SAMLConstants.SAML2_POST_BINDING_URI)));
                
                final Iterable<EntityDescriptor> it = samlResolver.resolve(criteriaSet);
                it.forEach(entityDescriptor -> {
                    final SPSSODescriptor spssoDescriptor = entityDescriptor.getSPSSODescriptor(SAMLConstants.SAML20P_NS);
                    final List<AssertionConsumerService> acsEndpoints = spssoDescriptor.getAssertionConsumerServices();
                    if (acsEndpoints.isEmpty()) {
                        throw new RuntimeException("Metadata resolved for entity id " + issuer + " has no defined ACS endpoints");
                    }
                    final int acsIndex = authnRequest.getAssertionConsumerServiceIndex();
                    if (acsIndex + 1 > acsEndpoints.size()) {
                        throw new RuntimeException("AssertionConsumerService index specified in the request " + acsIndex + " is invalid "
                                + "since the total endpoints available to " + issuer + " is " + acsEndpoints.size());
                    }
                    final AssertionConsumerService foundAcs = acsEndpoints.get(acsIndex);
                    acs.setBinding(foundAcs.getBinding());
                    acs.setLocation(foundAcs.getLocation());
                    acs.setResponseLocation(foundAcs.getResponseLocation());
                    acs.setIndex(acsIndex);
                    return;
                });
            } else {
                acs.setBinding(authnRequest.getProtocolBinding());
                acs.setLocation(authnRequest.getAssertionConsumerServiceURL());
                acs.setResponseLocation(authnRequest.getAssertionConsumerServiceURL());
                acs.setIndex(0);
                acs.setIsDefault(true);
            }
            
            LOGGER.debug("Resolved AssertionConsumerService from the request is {}", acs);
            if (StringUtils.isBlank(acs.getBinding())) {
                throw new SamlException("AssertionConsumerService has no protocol binding defined");
            }
            if (StringUtils.isBlank(acs.getLocation()) && StringUtils.isBlank(acs.getResponseLocation())) {
                throw new SamlException("AssertionConsumerService has no location or response location defined");
            }
            return acs;
        } catch (final Exception e) {
            throw new RuntimeException(new SamlException(e.getMessage(), e));
        }
    }

    /**
     * Gets issuer from saml request.
     *
     * @param request the request
     * @return the issuer from saml request
     */
    public static String getIssuerFromSamlRequest(final RequestAbstractType request) {
        return request.getIssuer().getValue();
    }
}


