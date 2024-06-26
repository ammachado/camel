/* Generated by camel build tools - do NOT edit this file! */
package org.apache.camel.component.azure.storage.datalake;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.camel.spi.EndpointUriFactory;

/**
 * Generated by camel build tools - do NOT edit this file!
 */
public class DataLakeEndpointUriFactory extends org.apache.camel.support.component.EndpointUriFactorySupport implements EndpointUriFactory {

    private static final String BASE = ":accountName/fileSystemName";

    private static final Set<String> PROPERTY_NAMES;
    private static final Set<String> SECRET_PROPERTY_NAMES;
    private static final Set<String> MULTI_VALUE_PREFIXES;
    static {
        Set<String> props = new HashSet<>(55);
        props.add("accountKey");
        props.add("accountName");
        props.add("backoffErrorThreshold");
        props.add("backoffIdleThreshold");
        props.add("backoffMultiplier");
        props.add("bridgeErrorHandler");
        props.add("clientId");
        props.add("clientSecret");
        props.add("clientSecretCredential");
        props.add("close");
        props.add("closeStreamAfterRead");
        props.add("credentialType");
        props.add("dataCount");
        props.add("dataLakeServiceClient");
        props.add("delay");
        props.add("directoryName");
        props.add("downloadLinkExpiration");
        props.add("exceptionHandler");
        props.add("exchangePattern");
        props.add("expression");
        props.add("fileDir");
        props.add("fileName");
        props.add("fileOffset");
        props.add("fileSystemName");
        props.add("greedy");
        props.add("initialDelay");
        props.add("lazyStartProducer");
        props.add("maxResults");
        props.add("maxRetryRequests");
        props.add("openOptions");
        props.add("operation");
        props.add("path");
        props.add("permission");
        props.add("pollStrategy");
        props.add("position");
        props.add("recursive");
        props.add("regex");
        props.add("repeatCount");
        props.add("retainUncommitedData");
        props.add("runLoggingLevel");
        props.add("sasCredential");
        props.add("sasSignature");
        props.add("scheduledExecutorService");
        props.add("scheduler");
        props.add("schedulerProperties");
        props.add("sendEmptyMessageWhenIdle");
        props.add("serviceClient");
        props.add("sharedKeyCredential");
        props.add("startScheduler");
        props.add("tenantId");
        props.add("timeUnit");
        props.add("timeout");
        props.add("umask");
        props.add("useFixedDelay");
        props.add("userPrincipalNameReturned");
        PROPERTY_NAMES = Collections.unmodifiableSet(props);
        Set<String> secretProps = new HashSet<>(5);
        secretProps.add("accountKey");
        secretProps.add("clientSecret");
        secretProps.add("clientSecretCredential");
        secretProps.add("sasCredential");
        secretProps.add("sasSignature");
        SECRET_PROPERTY_NAMES = Collections.unmodifiableSet(secretProps);
        Set<String> prefixes = new HashSet<>(1);
        prefixes.add("scheduler.");
        MULTI_VALUE_PREFIXES = Collections.unmodifiableSet(prefixes);
    }

    @Override
    public boolean isEnabled(String scheme) {
        return "azure-storage-datalake".equals(scheme);
    }

    @Override
    public String buildUri(String scheme, Map<String, Object> properties, boolean encode) throws URISyntaxException {
        String syntax = scheme + BASE;
        String uri = syntax;

        Map<String, Object> copy = new HashMap<>(properties);

        uri = buildPathParameter(syntax, uri, "accountName", null, false, copy);
        uri = buildPathParameter(syntax, uri, "fileSystemName", null, false, copy);
        uri = buildQueryParameters(uri, copy, encode);
        return uri;
    }

    @Override
    public Set<String> propertyNames() {
        return PROPERTY_NAMES;
    }

    @Override
    public Set<String> secretPropertyNames() {
        return SECRET_PROPERTY_NAMES;
    }

    @Override
    public Set<String> multiValuePrefixes() {
        return MULTI_VALUE_PREFIXES;
    }

    @Override
    public boolean isLenientProperties() {
        return false;
    }
}

