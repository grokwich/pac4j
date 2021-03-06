package org.pac4j.saml.metadata;

import net.shibboleth.utilities.java.support.xml.SerializeSupport;
import org.apache.commons.lang.RandomStringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.opensaml.core.xml.XMLObjectBuilder;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.io.MarshallerFactory;
import org.opensaml.saml.common.SAMLObjectBuilder;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.ext.saml2alg.DigestMethod;
import org.opensaml.saml.ext.saml2mdreqinit.RequestInitiator;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.AttributeConsumingService;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.Extensions;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.RequestedAttribute;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SingleLogoutService;
import org.opensaml.security.credential.UsageType;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.pac4j.saml.crypto.CredentialProvider;
import org.pac4j.saml.util.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Generates metadata object with standard values and overriden user defined values.
 *
 * @author Michael Remond
 * @since 1.5.0
 */
@SuppressWarnings("unchecked")
public class SAML2MetadataGenerator implements SAMLMetadataGenerator {

    protected final static Logger logger = LoggerFactory.getLogger(SAML2MetadataGenerator.class);

    protected final XMLObjectBuilderFactory builderFactory = Configuration.getBuilderFactory();

    protected final MarshallerFactory marshallerFactory = Configuration.getMarshallerFactory();

    protected CredentialProvider credentialProvider;

    protected String entityId;

    protected String assertionConsumerServiceUrl;

    protected String singleLogoutServiceUrl;

    protected boolean authnRequestSigned = false;

    protected boolean wantAssertionSigned = true;

    protected int defaultACSIndex = 0;

    protected String requestInitiatorLocation = null;

    protected String binding;

    protected String nameIdPolicyFormat = null;

    protected boolean signMetadata;

    protected List<SAML2ServiceProvicerRequestedAttribute> requestedAttributes = new ArrayList<>();

    public SAML2MetadataGenerator(final String binding) {
        this.binding = binding;
    }

    @Override
    public final MetadataResolver buildMetadataResolver() throws Exception {
        final EntityDescriptor md = buildEntityDescriptor();
        final Element entityDescriptorElement = this.marshallerFactory.getMarshaller(md).marshall(md);
        final DOMMetadataResolver resolver = new DOMMetadataResolver(entityDescriptorElement);
        resolver.setRequireValidMetadata(true);
        resolver.setFailFastInitialization(true);
        resolver.setId(resolver.getClass().getCanonicalName());
        resolver.initialize();
        return resolver;
    }

    @Override
    public final String getMetadata() throws Exception {
        final EntityDescriptor md = buildEntityDescriptor();
        final Element entityDescriptorElement = this.marshallerFactory.getMarshaller(md).marshall(md);
        return SerializeSupport.nodeToString(entityDescriptorElement);
    }

    @Override
    public final EntityDescriptor buildEntityDescriptor() {
        final SAMLObjectBuilder<EntityDescriptor> builder = (SAMLObjectBuilder<EntityDescriptor>)
            this.builderFactory.getBuilder(EntityDescriptor.DEFAULT_ELEMENT_NAME);
        final EntityDescriptor descriptor = builder.buildObject();
        descriptor.setEntityID(this.entityId);
        descriptor.setValidUntil(DateTime.now(DateTimeZone.UTC).plusYears(20));
        descriptor.setID(generateEntityDescriptorId());
        descriptor.setExtensions(generateMetadataExtensions());
        descriptor.getRoleDescriptors().add(buildSPSSODescriptor());

        if (this.signMetadata && credentialProvider != null) {
            final boolean result = signMetadata(descriptor);
            logger.info("SAML entity descriptor was signed: {}", result);
        }
        return descriptor;
    }

    private boolean signMetadata(final SignableSAMLObject signableSAMLObject) {
        final XMLObjectBuilder sigBuilder = this.builderFactory.getBuilder(Signature.DEFAULT_ELEMENT_NAME);
        final Signature signature = (Signature) sigBuilder.buildObject(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(this.credentialProvider.getCredential());
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        signature.setKeyInfo(this.credentialProvider.getKeyInfo());
        signableSAMLObject.setSignature(signature);
        try {
            Configuration.getMarshallerFactory().getMarshaller(signableSAMLObject).marshall(signableSAMLObject);
            Signer.signObject(signature);
            return true;
        } catch (final Exception e) {
            logger.error("Error happened during signing object", e);
        }
        return false;
    }

    protected final Extensions generateMetadataExtensions() {
        final SAMLObjectBuilder<Extensions> builderExt = (SAMLObjectBuilder<Extensions>)
            this.builderFactory.getBuilder(Extensions.DEFAULT_ELEMENT_NAME);

        final Extensions extensions = builderExt.buildObject();
        extensions.getNamespaceManager().registerAttributeName(DigestMethod.TYPE_NAME);

        final SAMLObjectBuilder<DigestMethod> builder = (SAMLObjectBuilder<DigestMethod>)
            this.builderFactory.getBuilder(DigestMethod.DEFAULT_ELEMENT_NAME);

        DigestMethod method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmlenc#sha512");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmldsig-more#sha384");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmlenc#sha256");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmldsig-more#sha224");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2000/09/xmldsig#sha1");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmldsig-more#rsa-sha512");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmldsig-more#rsa-sha384");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2000/09/xmldsig#rsa-sha1");
        extensions.getUnknownXMLObjects().add(method);

        method = builder.buildObject();
        method.setAlgorithm("http://www.w3.org/2000/09/xmldsig#dsa-sha1");
        extensions.getUnknownXMLObjects().add(method);

        return extensions;
    }

    protected final String generateEntityDescriptorId() {
        try {
            return "_".concat(RandomStringUtils.randomAlphanumeric(39)).toLowerCase();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected final SPSSODescriptor buildSPSSODescriptor() {
        final SAMLObjectBuilder<SPSSODescriptor> builder = (SAMLObjectBuilder<SPSSODescriptor>)
            this.builderFactory.getBuilder(SPSSODescriptor.DEFAULT_ELEMENT_NAME);
        final SPSSODescriptor spDescriptor = builder.buildObject();

        spDescriptor.setAuthnRequestsSigned(this.authnRequestSigned);
        spDescriptor.setWantAssertionsSigned(this.wantAssertionSigned);
        spDescriptor.addSupportedProtocol(SAMLConstants.SAML20P_NS);
        spDescriptor.addSupportedProtocol(SAMLConstants.SAML10P_NS);
        spDescriptor.addSupportedProtocol(SAMLConstants.SAML11P_NS);

        final SAMLObjectBuilder<Extensions> builderExt = (SAMLObjectBuilder<Extensions>)
            this.builderFactory.getBuilder(Extensions.DEFAULT_ELEMENT_NAME);

        final Extensions extensions = builderExt.buildObject();
        extensions.getNamespaceManager().registerAttributeName(RequestInitiator.DEFAULT_ELEMENT_NAME);

        final SAMLObjectBuilder<RequestInitiator> builderReq = (SAMLObjectBuilder<RequestInitiator>)
            this.builderFactory.getBuilder(RequestInitiator.DEFAULT_ELEMENT_NAME);

        final RequestInitiator requestInitiator = builderReq.buildObject();
        requestInitiator.setLocation(this.requestInitiatorLocation);
        requestInitiator.setBinding(RequestInitiator.DEFAULT_ELEMENT_NAME.getNamespaceURI());

        extensions.getUnknownXMLObjects().add(requestInitiator);
        spDescriptor.setExtensions(extensions);

        spDescriptor.getNameIDFormats().addAll(buildNameIDFormat());

        int index = 0;
        // Fix the POST binding for the response instead of using the binding of the request
        spDescriptor.getAssertionConsumerServices()
            .add(getAssertionConsumerService(SAMLConstants.SAML2_POST_BINDING_URI, index++, this.defaultACSIndex == index));

        if (credentialProvider != null) {
            spDescriptor.getKeyDescriptors().add(getKeyDescriptor(UsageType.SIGNING, this.credentialProvider.getKeyInfo()));
            spDescriptor.getKeyDescriptors().add(getKeyDescriptor(UsageType.ENCRYPTION, this.credentialProvider.getKeyInfo()));
        }

        if (!requestedAttributes.isEmpty()) {
            final SAMLObjectBuilder<AttributeConsumingService> attrServiceBuilder =
                (SAMLObjectBuilder<AttributeConsumingService>) this.builderFactory
                    .getBuilder(AttributeConsumingService.DEFAULT_ELEMENT_NAME);
            final AttributeConsumingService attributeService =
                attrServiceBuilder.buildObject(AttributeConsumingService.DEFAULT_ELEMENT_NAME);
            for (final SAML2ServiceProvicerRequestedAttribute attr : this.requestedAttributes) {
                final SAMLObjectBuilder<RequestedAttribute> attrBuilder = (SAMLObjectBuilder<RequestedAttribute>) this.builderFactory
                    .getBuilder(RequestedAttribute.DEFAULT_ELEMENT_NAME);
                final RequestedAttribute requestAttribute = attrBuilder.buildObject(RequestedAttribute.DEFAULT_ELEMENT_NAME);
                requestAttribute.setIsRequired(attr.isRequired());
                requestAttribute.setName(attr.getName());
                requestAttribute.setFriendlyName(attr.getFriendlyName());
                requestAttribute.setNameFormat(attr.getNameFormat());

                attributeService.getRequestAttributes().add(requestAttribute);
            }
            spDescriptor.getAttributeConsumingServices().add(attributeService);
        }
        return spDescriptor;

    }

    protected final Collection<NameIDFormat> buildNameIDFormat() {

        final SAMLObjectBuilder<NameIDFormat> builder = (SAMLObjectBuilder<NameIDFormat>) this.builderFactory
            .getBuilder(NameIDFormat.DEFAULT_ELEMENT_NAME);
        final Collection<NameIDFormat> formats = new ArrayList<>();

        if (this.nameIdPolicyFormat != null) {
            final NameIDFormat nameID = builder.buildObject();
            nameID.setFormat(this.nameIdPolicyFormat);
            formats.add(nameID);
        } else {
            final NameIDFormat transientNameID = builder.buildObject();
            transientNameID.setFormat(NameIDType.TRANSIENT);
            formats.add(transientNameID);
            final NameIDFormat persistentNameID = builder.buildObject();
            persistentNameID.setFormat(NameIDType.PERSISTENT);
            formats.add(persistentNameID);
            final NameIDFormat emailNameID = builder.buildObject();
            emailNameID.setFormat(NameIDType.EMAIL);
            formats.add(emailNameID);
            final NameIDFormat unspecNameID = builder.buildObject();
            unspecNameID.setFormat(NameIDType.UNSPECIFIED);
            formats.add(unspecNameID);
        }
        return formats;
    }

    protected final AssertionConsumerService getAssertionConsumerService(final String binding, final int index,
                                                                         final boolean isDefault) {
        final SAMLObjectBuilder<AssertionConsumerService> builder = (SAMLObjectBuilder<AssertionConsumerService>) this.builderFactory
            .getBuilder(AssertionConsumerService.DEFAULT_ELEMENT_NAME);
        final AssertionConsumerService consumer = builder.buildObject();
        consumer.setLocation(this.assertionConsumerServiceUrl);
        consumer.setBinding(binding);
        if (isDefault) {
            consumer.setIsDefault(true);
        }
        consumer.setIndex(index);
        return consumer;
    }

    protected SingleLogoutService getSingleLogoutService(final String binding) {
        final SAMLObjectBuilder<SingleLogoutService> builder = (SAMLObjectBuilder<SingleLogoutService>) this.builderFactory
            .getBuilder(SingleLogoutService.DEFAULT_ELEMENT_NAME);
        final SingleLogoutService logoutService = builder.buildObject();
        logoutService.setLocation(this.singleLogoutServiceUrl);
        logoutService.setBinding(binding);
        return logoutService;
    }

    protected final KeyDescriptor getKeyDescriptor(final UsageType type, final KeyInfo key) {
        final SAMLObjectBuilder<KeyDescriptor> builder = (SAMLObjectBuilder<KeyDescriptor>)
            Configuration.getBuilderFactory()
                .getBuilder(KeyDescriptor.DEFAULT_ELEMENT_NAME);
        final KeyDescriptor descriptor = builder.buildObject();
        descriptor.setUse(type);
        descriptor.setKeyInfo(key);
        return descriptor;
    }

    public CredentialProvider getCredentialProvider() {
        return this.credentialProvider;
    }

    public final void setCredentialProvider(final CredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    public String getEntityId() {
        return this.entityId;
    }

    public final void setEntityId(final String entityId) {
        this.entityId = entityId;
    }

    public boolean isAuthnRequestSigned() {
        return this.authnRequestSigned;
    }

    public final void setAuthnRequestSigned(final boolean authnRequestSigned) {
        this.authnRequestSigned = authnRequestSigned;
    }

    public boolean isWantAssertionSigned() {
        return this.wantAssertionSigned;
    }

    public void setWantAssertionSigned(final boolean wantAssertionSigned) {
        this.wantAssertionSigned = wantAssertionSigned;
    }

    public int getDefaultACSIndex() {
        return this.defaultACSIndex;
    }

    public void setDefaultACSIndex(final int defaultACSIndex) {
        this.defaultACSIndex = defaultACSIndex;
    }

    public final void setAssertionConsumerServiceUrl(final String assertionConsumerServiceUrl) {
        this.assertionConsumerServiceUrl = assertionConsumerServiceUrl;
    }

    public final void setSingleLogoutServiceUrl(final String singleLogoutServiceUrl) {
        this.singleLogoutServiceUrl = singleLogoutServiceUrl;
    }

    public final void setRequestInitiatorLocation(final String requestInitiatorLocation) {
        this.requestInitiatorLocation = requestInitiatorLocation;
    }

    public String getNameIdPolicyFormat() {
        return this.nameIdPolicyFormat;
    }

    public void setNameIdPolicyFormat(final String nameIdPolicyFormat) {
        this.nameIdPolicyFormat = nameIdPolicyFormat;
    }

    public boolean isSignMetadata() {
        return signMetadata;
    }

    public void setSignMetadata(final boolean signMetadata) {
        this.signMetadata = signMetadata;
    }

    public List<SAML2ServiceProvicerRequestedAttribute> getRequestedAttributes() {
        return requestedAttributes;
    }

    public void setRequestedAttributes(final List<SAML2ServiceProvicerRequestedAttribute> requestedAttributes) {
        this.requestedAttributes = requestedAttributes;
    }
}
