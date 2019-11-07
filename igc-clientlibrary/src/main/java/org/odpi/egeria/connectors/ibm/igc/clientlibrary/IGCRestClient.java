/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.ibm.igc.clientlibrary;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.common.*;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.types.TypeDetails;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.types.TypeHeader;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.types.TypeProperty;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.search.IGCSearch;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.search.IGCSearchCondition;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.search.IGCSearchConditionSet;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.search.IGCSearchSorting;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.update.IGCCreate;
import org.odpi.egeria.connectors.ibm.igc.clientlibrary.update.IGCUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.Base64Utils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Library of methods to connect to and interact with an IBM Information Governance Catalog environment
 * using appropriate session management.
 * <br><br>
 * Methods are provided to interact with REST API endpoints and process results as JsonNode objects
 * (ie. allowing direct traversal of the JSON objects) and through the use of registered POJOs to
 * automatically (de-)serialise between the JSON form and a native Java object.
 * <br><br>
 * The native Java objects for out-of-the-box Information Governance Catalog asset types have been
 * generated under org.odpi.openmetadata.adapters.repositoryservices.igc.model.* -- including different
 * versions depending on the environment to which you are connecting.
 * <br><br>
 * For additional examples of using the REST API (eg. potential criteria and operators for searching, etc), see:
 * <ul>
 *     <li><a href="http://www-01.ibm.com/support/docview.wss?uid=swg27047054">IGC REST API: Tips, tricks, and time-savers</a></li>
 *     <li><a href="http://www-01.ibm.com/support/docview.wss?uid=swg27047059">IGC REST API: Sample REST API calls and use case descriptions</a></li>
 * </ul>
 *
 * @see #registerPOJO(Class)
 */
public class IGCRestClient {

    private static final Logger log = LoggerFactory.getLogger(IGCRestClient.class);

    private String authorization;
    private String baseURL;
    private Boolean workflowEnabled = false;
    private List<String> cookies = null;
    private boolean successfullyInitialised = false;
    private RestTemplate restTemplate;

    private IGCVersionEnum igcVersion;
    private HashMap<String, Class> registeredPojosByType;
    private HashMap<String, DynamicPropertyReader> typeAndPropertyToAccessor;
    private HashMap<String, DynamicPropertyWriter> typeAndPropertyToWriter;

    private Set<String> typesThatCanBeCreated;
    private Set<String> typesThatIncludeModificationDetails;
    private Map<String, String> typeToDisplayName;
    private Map<String, List<String>> typeToNonRelationshipProperties;
    private Map<String, List<String>> typeToStringProperties;
    private Map<String, List<String>> typeToAllProperties;
    private Map<String, List<String>> typeToPagedRelationshipProperties;

    private int defaultPageSize = 100;

    private ObjectMapper mapper;
    private ObjectMapper typeMapper;

    private static final String EP_TYPES = "/ibm/iis/igc-rest/v1/types";
    private static final String EP_ASSET = "/ibm/iis/igc-rest/v1/assets";
    private static final String EP_SEARCH = "/ibm/iis/igc-rest/v1/search";
    private static final String EP_LOGOUT  = "/ibm/iis/igc-rest/v1/logout";
    private static final String EP_BUNDLES = "/ibm/iis/igc-rest/v1/bundles";
    private static final String EP_BUNDLE_ASSETS = EP_BUNDLES + "/assets";

    /**
     * Default constructor used by the IGCRestClient.
     * <br><br>
     * Creates a new session on the server and retains the cookies to re-use the same session for the life
     * of the client (or until the session times out); whichever occurs first.
     *
     * @param host the services (domain) tier host
     * @param port the services (domain) tier port number
     * @param user the username with which to open and retain the session
     * @param password the password for the user
     */
    public IGCRestClient(String host, String port, String user, String password) {
        this("https://" + host + ":" + port, user, password);
    }

    /**
     * Creates a new session on the server and retains the cookies to re-use the same session for the life
     * of the client (or until the session times out); whichever occurs first.
     *
     * @param baseURL the base URL of the domain tier of Information Server
     * @param user the username with which to open and retain the session
     * @param password the password of the user
     */
    public IGCRestClient(String baseURL, String user, String password) {
        this(baseURL, encodeBasicAuth(user, password));
    }

    /**
     * Creates a new session on the server and retains the cookies to re-use the same session for the life
     * of the client (or until the session times out); whichever occurs first.
     *
     * @param baseURL the base URL of the domain tier of Information Server
     * @param authorization the Basic-encoded authorization string to use to login to Information Server
     */
    protected IGCRestClient(String baseURL, String authorization) {

        if (baseURL == null || !baseURL.startsWith("https://")) {
            if (log.isErrorEnabled()) { log.error("Cannot instantiate IGCRestClient -- baseURL must be https: {}", baseURL); }
            throw new NullPointerException();
        }

        this.baseURL = baseURL;
        this.authorization = authorization;
        this.mapper = new ObjectMapper();
        this.typeMapper = new ObjectMapper();
        this.registeredPojosByType = new HashMap<>();
        this.typeAndPropertyToAccessor = new HashMap<>();
        this.typeAndPropertyToWriter = new HashMap<>();
        this.restTemplate = new RestTemplate();

        // Ensure that the REST template always uses UTF-8
        List<HttpMessageConverter<?>> converters = restTemplate.getMessageConverters();
        converters.removeIf(httpMessageConverter -> httpMessageConverter instanceof StringHttpMessageConverter);
        converters.add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));

        this.typesThatCanBeCreated = new HashSet<>();
        this.typesThatIncludeModificationDetails = new HashSet<>();
        this.typeToDisplayName = new HashMap<>();
        this.typeToNonRelationshipProperties = new HashMap<>();
        this.typeToStringProperties = new HashMap<>();
        this.typeToAllProperties = new HashMap<>();
        this.typeToPagedRelationshipProperties = new HashMap<>();

        if (log.isDebugEnabled()) { log.debug("Constructing IGCRestClient..."); }

        // Run a simple initial query to obtain a session and setup the cookies
        if (this.authorization != null) {

            IGCSearch igcSearch = new IGCSearch("category");
            igcSearch.addType("term");
            igcSearch.addType("information_governance_policy");
            igcSearch.addType("information_governance_rule");
            igcSearch.setPageSize(1);
            igcSearch.setDevGlossary(true);
            String response = searchJson(igcSearch);

            if (response != null) {

                if (log.isDebugEnabled()) { log.debug("Checking for workflow and registering version..."); }
                ObjectMapper tmpMapper = new ObjectMapper();
                try {
                    this.workflowEnabled = tmpMapper.readValue(response, new TypeReference<ItemList<Reference>>(){}).getPaging().getNumTotal() > 0;
                } catch (IOException e) {
                    if (log.isErrorEnabled()) { log.error("Unable to determine if workflow is enabled.", e); }
                }
                // Register the non-generated types
                //this.registerPOJO(Paging.class);

                // Start with lowest version supported
                this.igcVersion = IGCVersionEnum.values()[0];
                List<TypeHeader> igcTypes = getTypes(tmpMapper);
                Set<String> typeNames = igcTypes.stream().map(TypeHeader::getId).collect(Collectors.toSet());
                for (IGCVersionEnum aVersion : IGCVersionEnum.values()) {
                    if (aVersion.isHigherThan(this.igcVersion)
                            && typeNames.contains(aVersion.getTypeNameFirstAvailableInThisVersion())
                            && !typeNames.contains(aVersion.getTypeNameNotAvailableInThisVersion())) {
                        this.igcVersion = aVersion;
                    }
                }
                if (log.isInfoEnabled()) { log.info("Detected IGC version: {}", this.igcVersion.getVersionString()); }
                successfullyInitialised = true;

            } else {
                log.error("Unable to construct IGCRestClient: no authorization provided.");
            }

        }

    }

    /**
     * Indicates whether the client was successfully initialised (true) or not (false).
     *
     * @return boolean
     */
    public boolean isSuccessfullyInitialised() { return successfullyInitialised; }

    /**
     * Setup the HTTP headers of a request based on either session reuse (forceLogin = false) or forcing a new
     * session (forceLogin = true).
     *
     * @param forceLogin indicates whether to create a new session by forcing login (true), or reuse existing session (false)
     * @return HttpHeaders
     */
    private HttpHeaders getHttpHeaders(boolean forceLogin) {

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");

        // If we have cookies already, and haven't been asked to force the login,
        // re-use these (to maintain the same session)
        if (cookies != null && !forceLogin) {
            // TODO: identified as High issue on page 1122
            headers.addAll(HttpHeaders.COOKIE, cookies);
        } else { // otherwise re-authenticate by Basic authentication
            String auth = "Basic " + this.authorization;
            headers.add(HttpHeaders.AUTHORIZATION, auth);
        }

        return headers;

    }

    /**
     * Attempts to open a new session while sending the provided request. If the alreadyTriedNewSession is true,
     * and we are unable to open a new session with this attempt, will give up. If the alreadyTriedNewSession is false,
     * will attempt to re-send this request to open a new session precisely once before giving up.
     *
     * @param url the URL to which to send the request
     * @param method the HTTP method to use in sending the request
     * @param contentType the type of content to expect in the payload (if any)
     * @param payload the payload (if any) for the request
     * @param alreadyTriedNewSession indicates whether a new session was already attempted (true) or not (false)
     * @return {@code ResponseEntity<String>}
     */
    private ResponseEntity<String> openNewSessionWithRequest(String url,
                                                             HttpMethod method,
                                                             MediaType contentType,
                                                             String payload,
                                                             boolean alreadyTriedNewSession) {
        if (alreadyTriedNewSession) {
            if (log.isErrorEnabled()) { log.error("Opening a new session already attempted without success -- giving up on {} to {} with {}", method, url, payload); }
            return null;
        } else {
            // By removing cookies, we'll force a login
            this.cookies = null;
            return makeRequest(url, method, contentType, payload, true);
        }
    }

    /**
     * Attempts to open a new session while uploading the provided file. If the alreadyTriedNewSession is true,
     * and we are unable to open a new session with this attempt, will give up. If the alreadyTriedNewSession is false,
     * will attempt to re-upload the file to open a new session precisely once before giving up.
     *
     * @param endpoint the endpoint to which to upload the file
     * @param method the HTTP method to use in sending the request
     * @param file the Spring FileSystemResource or ClassPathResource containing the file to be uploaded
     * @param alreadyTriedNewSession indicates whether a new session was already attempted (true) or not (false)
     * @return {@code ResponseEntity<String>}
     */
    private ResponseEntity<String> openNewSessionWithUpload(String endpoint,
                                                            HttpMethod method,
                                                            AbstractResource file,
                                                            boolean alreadyTriedNewSession) {
        if (alreadyTriedNewSession) {
            if (log.isErrorEnabled()) { log.error("Opening a new session already attempted without success -- giving up on {} to {} with {}", method, endpoint, file); }
            return null;
        } else {
            log.info("Session appears to have timed out -- starting a new session and re-trying the upload.");
            // By removing cookies, we'll force a login
            this.cookies = null;
            return uploadFile(endpoint, method, file, true);
        }
    }

    /**
     * Adds the cookies from a response into subsequent headers, so that we re-use the session indicated by those
     * cookies.
     *
     * @param response the response from which to obtain the cookies
     */
    private void setCookiesFromResponse(ResponseEntity<String> response) {

        // If we had a successful response, setup the cookies
        if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
            HttpHeaders headers = response.getHeaders();
            if (headers.get(HttpHeaders.SET_COOKIE) != null) {
                this.cookies = headers.get(HttpHeaders.SET_COOKIE);
            }
        } else {
            if (log.isErrorEnabled()) { log.error("Unable to make request or unexpected status: {}", response.getStatusCode()); }
        }

    }

    /**
     * Attempt to convert the JSON string into a Java object, based on the registered POJOs.
     *
     * @param json the JSON string to convert
     * @return Reference - an IGC object
     */
    public Reference readJSONIntoPOJO(String json) {
        Reference reference = null;
        try {
            reference = this.mapper.readValue(json, Reference.class);
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to translate JSON into POJO: {}", json, e); }
        }
        return reference;
    }

    /**
     * Attempt to convert the JSON string into an ItemList.
     *
     * @param json the JSON string to convert
     * @param <T> the type of items that should be in the ItemList
     * @return {@code ItemList<T>}
     */
    public <T extends Reference> ItemList<T> readJSONIntoItemList(String json) {
        ItemList<T> itemList = null;
        try {
            itemList = this.mapper.readValue(json, new TypeReference<ItemList<T>>(){});
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to translate JSON into ItemList: {}", json, e); }
        }
        return itemList;
    }

    /**
     * Attempt to convert the provided IGC object into JSON, based on the registered POJOs.
     *
     * @param asset the IGC asset to convert
     * @return String of JSON representing the asset
     */
    public String getValueAsJSON(Reference asset) {
        String payload = null;
        try {
            payload = this.mapper.writeValueAsString(asset);
        } catch (JsonProcessingException e) {
            if (log.isErrorEnabled()) { log.error("Unable to translate asset into JSON: {}", asset, e); }
        }
        return payload;
    }

    /**
     * Retrieve the version of the IGC environment (static member VERSION_115 or VERSION_117).
     *
     * @return IGCVersionEnum
     */
    public IGCVersionEnum getIgcVersion() { return igcVersion; }

    /**
     * Retrieve the base URL of this IGC REST API connection.
     *
     * @return String
     */
    public String getBaseURL() { return baseURL; }

    /**
     * Retrieve the default page size for this IGC REST API connection.
     *
     * @return int
     */
    public int getDefaultPageSize() { return defaultPageSize; }

    /**
     * Set the default page size for this IGC REST API connection.
     *
     * @param pageSize the new default page size to use
     */
    public void setDefaultPageSize(int pageSize) { this.defaultPageSize = pageSize; }

    /**
     * Utility function to easily encode a username and password to send through as authorization info.
     *
     * @param username username to encode
     * @param password password to encode
     * @return String of appropriately-encoded credentials for authorization
     */
    private static String encodeBasicAuth(String username, String password) {
        return Base64Utils.encodeToString((username + ":" + password).getBytes(UTF_8));
    }

    /**
     * Internal utility for making potentially repeat requests (if session expires and needs to be re-opened),
     * to upload a file to a given endpoint.
     *
     * @param endpoint the REST resource against which to POST the upload
     * @param file the Spring FileSystemResource or ClassPathResource of the file to be uploaded
     * @param forceLogin a boolean indicating whether login should be forced (true) or session reused (false)
     * @return {@code ResponseEntity<String>}
     */
    private ResponseEntity<String> uploadFile(String endpoint, HttpMethod method, AbstractResource file, boolean forceLogin) {

        HttpHeaders headers = getHttpHeaders(forceLogin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = null;
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);

        HttpEntity<MultiValueMap<String, Object>> toSend = new HttpEntity<>(body, headers);

        try {
            response = restTemplate.exchange(
                    baseURL + endpoint,
                    method,
                    toSend,
                    String.class
            );
        } catch (HttpClientErrorException e) {
            log.warn("Request failed -- session may have expired, retrying...", e);
            // If the response was forbidden (fails with exception), the session may have expired -- create a new one
            response = openNewSessionWithUpload(
                    baseURL + endpoint,
                    method,
                    file,
                    forceLogin
            );
        } catch (RestClientException e) {
            log.error("Request failed -- check IGC environment connectivity and authentication details.", e);
        }

        return response;

    }

    /**
     * General utility for uploading binary files.
     *
     * @param endpoint the REST resource against which to upload the file
     * @param method HttpMethod (POST, PUT, etc)
     * @param file the Spring FileSystemResource or ClassPathResource containing the file to be uploaded
     * @return boolean - indicates success (true) or failure (false)
     */
    public boolean uploadFile(String endpoint, HttpMethod method, AbstractResource file) {
        ResponseEntity<String> response = uploadFile(endpoint, method, file, false);
        return (response == null ? false : response.getStatusCode() == HttpStatus.OK);
    }

    /**
     * Internal utility for making potentially repeat requests (if session expires and needs to be re-opened).
     *
     * @param url the URL against which to make the request
     * @param method HttpMethod (GET, POST, etc)
     * @param contentType the type of content to expect in the payload (if any)
     * @param payload if POSTing some content, the JSON structure providing what should be POSTed
     * @param forceLogin a boolean indicating whether login should be forced (true) or session reused (false)
     * @return {@code ResponseEntity<String>}
     */
    private ResponseEntity<String> makeRequest(String url,
                                               HttpMethod method,
                                               MediaType contentType,
                                               String payload,
                                               boolean forceLogin) {
        HttpHeaders headers = getHttpHeaders(forceLogin);
        HttpEntity<String> toSend;
        if (payload != null) {
            headers.setContentType(contentType);
            toSend = new HttpEntity<>(payload, headers);
        } else {
            toSend = new HttpEntity<>(headers);
        }
        ResponseEntity<String> response = null;
        try {
            if (log.isDebugEnabled()) { log.debug("{}ing to {} with: {}", method, url, payload); }
            UriComponents uriComponents = UriComponentsBuilder.fromHttpUrl(url).build(true);
            response = restTemplate.exchange(
                    uriComponents.toUri(),
                    method,
                    toSend,
                    String.class);
            setCookiesFromResponse(response);
        } catch (HttpClientErrorException e) {
            log.warn("Request failed -- session may have expired, retrying...", e);
            // If the response was forbidden (fails with exception), the session may have expired -- create a new one
            response = openNewSessionWithRequest(
                    url,
                    method,
                    contentType,
                    payload,
                    forceLogin
            );
        } catch (RestClientException e) {
            log.error("Request failed -- check IGC environment connectivity and authentication details.", e);
        }
        return response;
    }

    /**
     * General utility for making requests.
     *
     * @param endpoint the REST resource against which to make the request
     * @param method HttpMethod (GET, POST, etc)
     * @param contentType the type of content to expect in the payload (if any)
     * @param payload if POSTing some content, the JSON structure providing what should be POSTed
     * @return String - containing the body of the response
     */
    public String makeRequest(String endpoint, HttpMethod method, MediaType contentType, String payload) {
        ResponseEntity<String> response = makeRequest(
                baseURL + endpoint,
                method,
                contentType,
                payload,
                false
        );
        String body = null;
        if (response == null) {
            log.error("Unable to complete request -- check IGC environment connectivity and authentication details.");
            throw new NullPointerException("Unable to complete request -- check IGC environment connectivity and authentication details.");
        } else if (response.hasBody()) {
            body = response.getBody();
        }
        return body;
    }

    /**
     * General utility for making creation requests.
     *
     * @param endpoint the REST resource against which to make the request
     * @param method HttpMethod (POST, PUT, etc)
     * @param contentType the type of content to expect in the payload
     * @param payload the data that should be created
     * @return String - containing the RID of the created object instance
     */
    public String makeCreateRequest(String endpoint, HttpMethod method, MediaType contentType, String payload) {
        ResponseEntity<String> response = makeRequest(
                baseURL + endpoint,
                method,
                contentType,
                payload,
                false
        );
        String rid = null;
        if (response == null) {
            log.error("Unable to create instance -- check IGC environment connectivity and authentication details.");
            throw new NullPointerException("Unable to create instance -- check IGC environment connectivity and authentication details.");
        } else if (!response.getStatusCode().equals(HttpStatus.CREATED)) {
            log.error("Unable to create instance -- check IGC environment connectivity and authentication details.");
            throw new NullPointerException("Unable to create instance -- check IGC environment connectivity and authentication details.");
        } else {
            HttpHeaders headers = response.getHeaders();
            List<String> instanceURLs = headers.get("Location");
            if (instanceURLs != null && instanceURLs.size() == 1) {
                String instanceURL = instanceURLs.get(0);
                rid = instanceURL.substring(instanceURL.lastIndexOf("/") + 1);
            }
        }
        return rid;
    }

    /**
     * Retrieves the list of metadata types supported by IGC.
     *
     * @param objectMapper an ObjectMapper to use for translating the types list
     *
     * @return {@code List<TypeHeader>} the list of types supported by IGC
     */
    public List<TypeHeader> getTypes(ObjectMapper objectMapper) {
        String response = makeRequest(EP_TYPES, HttpMethod.GET, null,null);
        List<TypeHeader> alTypes = new ArrayList<>();
        try {
            alTypes = objectMapper.readValue(response, new TypeReference<List<TypeHeader>>(){});
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to parse types response: {}", response, e); }
        }
        return alTypes;
    }

    /**
     * Retrieves the type details (all properties and their details) for the provided type name in IGC.
     *
     * @param typeName the IGC type name for which to retrieve details
     * @return TypeDetails
     */
    public TypeDetails getTypeDetails(String typeName) {
        String response = makeRequest(EP_TYPES + "/" + typeName + "?showViewProperties=true&showCreateProperties=true&showEditProperties=true", HttpMethod.GET, null, null);
        TypeDetails typeDetails = null;
        try {
            typeDetails = typeMapper.readValue(response, TypeDetails.class);
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to parse type details response: {}", response, e); }
        }
        return typeDetails;
    }

    /**
     * Retrieve all information about an asset from IGC.
     * This can be an expensive operation that may retrieve far more information than you actually need.
     *
     * @see #getAssetRefById(String)
     *
     * @param rid the Repository ID of the asset
     * @return Reference - the IGC object representing the asset
     */
    public Reference getAssetById(String rid) {
        return readJSONIntoPOJO(makeRequest(EP_ASSET + "/" + rid, HttpMethod.GET, null,null));
    }

    /**
     * Retrieve only the minimal unique properties of an asset from IGC.
     * This will generally be the most performant way to see that an asset exists and get its identifying characteristics.
     *
     * @param rid the Repository ID of the asset
     * @return Reference - the minimalistic IGC object representing the asset
     */
    public Reference getAssetRefById(String rid) {

        // We can search for any object by ID by using "main_object" as the type
        // (no properties needed)
        IGCSearchCondition condition = new IGCSearchCondition(
                "_id",
                "=",
                rid
        );
        IGCSearchConditionSet conditionSet = new IGCSearchConditionSet(condition);
        IGCSearch igcSearch = new IGCSearch("main_object", conditionSet);
        // Add non-main_object types that might also be looked-up by RID
        igcSearch.addType("classification");
        igcSearch.addType("label");
        igcSearch.addType("user");
        igcSearch.addType("group");
        ItemList<Reference> results = search(igcSearch);
        Reference reference = null;
        if (results.getPaging().getNumTotal() > 0) {
            if (results.getPaging().getNumTotal() > 1) {
                if (log.isWarnEnabled()) { log.warn("Found multiple assets for RID {}, taking only the first.", rid); }
            }
            reference = results.getItems().get(0);
        }

        return reference;

    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  List<String> properties) {
        return getAssetWithSubsetOfProperties(rid, assetType, properties, defaultPageSize, null);
    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  String[] properties) {
        return getAssetWithSubsetOfProperties(rid, assetType, properties, defaultPageSize, null);
    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param pageSize the maximum number of each of the asset's relationships to return on this request
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  List<String> properties,
                                                                  int pageSize) {
        return getAssetWithSubsetOfProperties(rid, assetType, properties, pageSize, null);
    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param pageSize the maximum number of each of the asset's relationships to return on this request
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  String[] properties,
                                                                  int pageSize) {
        return getAssetWithSubsetOfProperties(rid, assetType, properties, pageSize, null);
    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param pageSize the maximum number of each of the asset's relationships to return on this request
     * @param sorting the sorting criteria to use for the results
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  List<String> properties,
                                                                  int pageSize,
                                                                  IGCSearchSorting sorting) {
        if (log.isDebugEnabled()) { log.debug("Retrieving asset {} with subset of details: {}", rid, properties); }
        T assetWithProperties = null;
        IGCSearchCondition idOnly = new IGCSearchCondition("_id", "=", rid);
        IGCSearchConditionSet idOnlySet = new IGCSearchConditionSet(idOnly);
        IGCSearch igcSearch = new IGCSearch(IGCRestConstants.getAssetTypeForSearch(assetType), properties, idOnlySet);
        if (pageSize > 0) {
            igcSearch.setPageSize(pageSize);
        }
        if (sorting != null) {
            igcSearch.addSortingCriteria(sorting);
        }
        ItemList<T> assetsWithProperties = search(igcSearch);
        if (!assetsWithProperties.getItems().isEmpty()) {
            assetWithProperties = assetsWithProperties.getItems().get(0);
        }
        return assetWithProperties;
    }

    /**
     * This will generally be the most performant method by which to retrieve asset information, when only
     * some subset of properties is required
     *
     * @param rid the repository ID (RID) of the asset to retrieve
     * @param assetType the IGC asset type of the asset to retrieve
     * @param properties a list of the properties to retrieve
     * @param pageSize the maximum number of each of the asset's relationships to return on this request
     * @param sorting the sorting criteria to use for the results
     * @param <T> the type of Reference to return
     * @return Reference - the object including only the subset of properties specified
     */
    public <T extends Reference> T getAssetWithSubsetOfProperties(String rid,
                                                                  String assetType,
                                                                  String[] properties,
                                                                  int pageSize,
                                                                  IGCSearchSorting sorting) {
        return getAssetWithSubsetOfProperties(rid, assetType, Arrays.asList(properties), pageSize, sorting);
    }

    /**
     * Retrieve all assets that match the provided search criteria from IGC.
     *
     * @param igcSearch the IGCSearch object defining criteria by which to search
     * @return JsonNode - the first JSON page of results from the search
     */
    public String searchJson(IGCSearch igcSearch) {
        return makeRequest(EP_SEARCH, HttpMethod.POST, MediaType.APPLICATION_JSON, igcSearch.getQuery().toString());
    }

    /**
     * Retrieve all assets that match the provided search criteria from IGC.
     *
     * @param igcSearch search conditions and criteria to use
     * @param <T> the type of items that should be in the ItemList
     * @return {@code ItemList<T>} - the first page of results from the search
     */
    public <T extends Reference> ItemList<T> search(IGCSearch igcSearch) {
        ItemList<T> itemList = null;
        String results = searchJson(igcSearch);
        try {
            itemList = this.mapper.readValue(results, new TypeReference<ItemList<T>>(){});
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to translate JSON results: {}", results, e); }
        }
        return itemList;
    }

    /**
     * Update the asset specified by the provided RID with the value(s) provided.
     *
     * @param rid the Repository ID of the asset to update
     * @param value the JSON structure defining what value(s) of the asset to update (and mode)
     * @return String - the JSON indicating the updated asset's RID and updates made
     */
    private String updateJson(String rid, JsonNode value) {
        return makeRequest(EP_ASSET + "/" + rid, HttpMethod.PUT, MediaType.APPLICATION_JSON, value.toString());
    }

    /**
     * Apply the update described by the provided update object.
     *
     * @param igcUpdate update criteria to use
     * @return boolean - indicating success (true) or not (false) of the operation
     */
    public boolean update(IGCUpdate igcUpdate) {
        String result = updateJson(igcUpdate.getRidToUpdate(), igcUpdate.getUpdate());
        return (result != null);
    }

    /**
     * Create the asset specified by the provided value(s).
     *
     * @param value the JSON structure defining what should be created
     * @return String - the created asset's RID
     */
    private String createJson(JsonNode value) {
        return makeCreateRequest(EP_ASSET, HttpMethod.POST, MediaType.APPLICATION_JSON, value.toString());
    }

    /**
     * Create the object described by the provided create object.
     *
     * @param igcCreate creation criteria to use
     * @return String - the created asset's RID (or null if nothing was created)
     */
    public String create(IGCCreate igcCreate) {
        return createJson(igcCreate.getCreate());
    }

    /**
     * Delete the asset specified by the provided RID.
     *
     * @param rid the RID of the asset to delete
     * @return String - null upon successful deletion, otherwise containing a message pertaining to the failure
     */
    private String deleteJson(String rid) {
        return makeRequest(EP_ASSET + "/" + rid, HttpMethod.DELETE, MediaType.APPLICATION_JSON, null);
    }

    /**
     * Delete the object specified by the provided RID.
     *
     * @param rid the RID of the asset to delete
     * @return boolean
     */
    public boolean delete(String rid) {
        String result = deleteJson(rid);
        if (result != null) {
            log.error("Unable to delete asset {}: {}", rid, result);
        }
        return (result == null);
    }

    /**
     * Upload the specified bundle, creating it if it does not already exist or updating it if it does.
     *
     * @param name the bundleId of the bundle
     * @param file the Spring FileSystemResource or ClassPathResource containing the file to be uploaded
     * @return boolean - indication of success (true) or failure (false)
     */
    public boolean upsertOpenIgcBundle(String name, AbstractResource file) {
        boolean success;
        List<String> existingBundles = getOpenIgcBundles();
        if (existingBundles.contains(name)) {
            success = uploadFile(EP_BUNDLES, HttpMethod.PUT, file);
        } else {
            success = uploadFile(EP_BUNDLES, HttpMethod.POST, file);
        }
        return success;
    }

    /**
     * Generates an OpenIGC bundle zip file from the provided directory path, and returns the temporary file it creates.
     *
     * @param directory the directory under which the OpenIGC bundle is defined (ie. including an
     *                  'asset_type_descriptor.xml', an 'i18n' subdirectory and an 'icons' subdirectory)
     * @return File - the temporary zip file containing the bundle
     */
    public File createOpenIgcBundleFile(File directory) {

        File bundle = null;
        try {
            bundle = File.createTempFile("openigc", "zip");
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to create temporary file needed for OpenIGC bundle from directory: {}", directory, e); }
        }
        if (bundle != null) {
            try (
                    FileOutputStream bundleOut = new FileOutputStream(bundle);
                    ZipOutputStream zipOutput = new ZipOutputStream(bundleOut)
            ) {
                if (!directory.isDirectory()) {
                    if (log.isErrorEnabled()) { log.error("Provided bundle location is not a directory: {}", directory); }
                } else {
                    recursivelyZipFiles(directory, "", zipOutput);
                }

            } catch (IOException e) {
                if (log.isErrorEnabled()) { log.error("Unable to create temporary file needed for OpenIGC bundle from directory: {}", directory, e); }
            }
        }
        return bundle;

    }

    /**
     * Recursively traverses the provided file to build up a zip file output in the provided ZipOutputStream.
     *
     * @param file the file from which to recursively process
     * @param name the name of the file from which to recursively process
     * @param zipOutput the zip output stream into which to write the entries
     */
    private void recursivelyZipFiles(File file, String name, ZipOutputStream zipOutput) {

        if (file.isDirectory()) {

            // Make sure the directory name ends with a separator
            String directoryName = name;
            if (!directoryName.equals("")) {
                directoryName = directoryName.endsWith(File.separator) ? directoryName : directoryName + File.separator;
            }

            // Create an entry in the zip file for the directory, then recurse on the files within it
            try {
                if (!directoryName.equals("")) {
                    zipOutput.putNextEntry(new ZipEntry(directoryName));
                }
                File[] files = file.listFiles();
                for (File subFile : files) {
                    recursivelyZipFiles(subFile, directoryName + subFile.getName(), zipOutput);
                }
            } catch (IOException e) {
                if (log.isErrorEnabled()) { log.error("Unable to create directory entry in zip file for {}.", directoryName, e); }
            }

        } else {

            try (FileInputStream fileInput = new FileInputStream(file)) {
                // Add an entry for the file into the zip file, and write its bytes into the zipfile output
                zipOutput.putNextEntry(new ZipEntry(name));
                byte[] buffer = new byte[1024];
                int length;
                while ((length = fileInput.read(buffer)) >= 0) {
                    zipOutput.write(buffer, 0, length);
                }
            } catch (FileNotFoundException e) {
                if (log.isErrorEnabled()) { log.error("Unable to find file: {}", file, e); }
            } catch (IOException e) {
                if (log.isErrorEnabled()) { log.error("Unable to read/write file: {}", file, e); }
            }

        }

    }

    /**
     * Retrieve the set of OpenIGC bundles already defined in the environment.
     *
     * @return {@code List<String>}
     */
    public List<String> getOpenIgcBundles() {
        String bundles = makeRequest(EP_BUNDLES, HttpMethod.GET, null,null);
        List<String> alBundles = new ArrayList<>();
        try {
            ArrayNode anBundles = mapper.readValue(bundles, ArrayNode.class);
            for (int i = 0; i < anBundles.size(); i++) {
                alBundles.add(anBundles.get(i).asText());
            }
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to parse bundle response: {}", bundles, e); }
        }
        return alBundles;
    }

    /**
     * Upload the provided OpenIGC asset XML: creating the asset(s) if they do not exist, updating if they do.
     *
     * @param assetXML the XML string defining the OpenIGC asset
     * @return String - the JSON structure indicating the updated assets' RID(s)
     */
    public String upsertOpenIgcAsset(String assetXML) {
        return makeRequest(EP_BUNDLE_ASSETS, HttpMethod.POST, MediaType.APPLICATION_XML, assetXML);
    }

    /**
     * Delete using the provided OpenIGC asset XML: deleting the asset(s) specified within it.
     *
     * @param assetXML the XML string defining the OpenIGC asset deletion
     * @return boolean - true on success, false on failure
     */
    public boolean deleteOpenIgcAsset(String assetXML) {
        return (makeRequest(EP_BUNDLE_ASSETS, HttpMethod.DELETE, MediaType.APPLICATION_XML, assetXML) == null);
    }

    /**
     * Retrieve the next page of results from a set of paging details, or if there is no next page return an empty
     * ItemList.
     *
     * @param paging the "paging" portion of the JSON response from which to retrieve the next page
     * @param <T> the type of items to expect in the ItemList
     * @return {@code ItemList<T>} - the next page of results
     */
    public <T extends Reference> ItemList<T> getNextPage(Paging paging) {
        ItemList<T> nextPage = null;
        try {
            nextPage = mapper.readValue("{}", new TypeReference<ItemList<T>>() {});
            String sNextURL = paging.getNextPageURL();
            if (sNextURL != null && !sNextURL.equals("null")) {
                if (this.workflowEnabled && !sNextURL.contains("workflowMode=draft")) {
                    sNextURL += "&workflowMode=draft";
                }
                // Strip off the hostname and port number details from the IGC response, to replace with details used
                // in configuration of the connector (allowing a proxy or other server in front)
                UriComponents components = UriComponentsBuilder.fromHttpUrl(sNextURL).build(true);
                String embeddedHost = "https://" + components.getHost() + ":" + components.getPort();
                String nextUrlNoHost = sNextURL.substring(embeddedHost.length() + 1);
                String nextPageBody = makeRequest(nextUrlNoHost, HttpMethod.GET, null, null);
                // If the page is part of an ASSET retrieval, we need to strip off the attribute
                // name of the relationship for proper multi-page composition
                if (sNextURL.contains(EP_ASSET)) {
                    String remainder = sNextURL.substring((baseURL + EP_ASSET).length() + 2);
                    String attributeName = remainder.substring(remainder.indexOf('/') + 1, remainder.indexOf('?'));
                    nextPageBody = nextPageBody.substring(attributeName.length() + 4, nextPageBody.length() - 1);
                }
                nextPage = mapper.readValue(nextPageBody, new TypeReference<ItemList<T>>() {});
            }
        } catch (IOException e) {
            if (log.isErrorEnabled()) { log.error("Unable to parse next page from JSON: {}", paging, e); }
        }
        return nextPage;
    }

    /**
     * Retrieve all pages of results from a set of Paging details and items, or if there is no next page return the
     * items provided.
     *
     * @param items the List of items for which to retrieve all pages
     * @param paging the Paging object for which to retrieve all pages
     * @param <T> the type of items to expect in the ItemList
     * @return {@code List<Reference>} - a List containing all items from all pages of results
     */
    public <T extends Reference> List<T> getAllPages(List<T> items, Paging paging) {
        List<T> allPages = items;
        ItemList<T> results = getNextPage(paging);
        List<T> resultsItems = results.getItems();
        if (!resultsItems.isEmpty()) {
            // NOTE: this ordering of addAll is important, to avoid side-effecting the original set of items
            resultsItems.addAll(allPages);
            allPages = getAllPages(resultsItems, results.getPaging());
        }
        return allPages;
    }

    /**
     * Disconnect from IGC REST API and invalidate the session.
     */
    public void disconnect() {
        makeRequest(EP_LOGOUT, HttpMethod.GET, null,null);
    }

    /**
     * Cache detailed information about the IGC object type.
     *
     * @param typeName name of the IGC object type to cache
     */
    public void cacheTypeDetails(String typeName) {

        // Only continue if the information is not already cached
        if (!typeToDisplayName.containsKey(typeName)) {
            TypeDetails typeDetails = getTypeDetails(typeName);

            // Cache whether the type supports creation or not
            if (typeDetails.getCreateInfo() != null) {
                List<TypeProperty> create = typeDetails.getCreateInfo().getProperties();
                if (create != null && !create.isEmpty()) {
                    typesThatCanBeCreated.add(typeName);
                }
            }

            // Cache property details
            List<TypeProperty> view = typeDetails.getViewInfo().getProperties();
            if (view != null) {
                List<String> allProperties = new ArrayList<>();
                List<String> nonRelationship = new ArrayList<>();
                List<String> stringProperties = new ArrayList<>();
                List<String> pagedRelationship = new ArrayList<>();
                for (TypeProperty property : view) {
                    String propertyName = property.getName();
                    if (!IGCRestConstants.getPropertiesToIgnore().contains(propertyName)) {
                        if (propertyName.equals("created_on")) {
                            typesThatIncludeModificationDetails.add(typeName);
                            // Instantiate and cache generic property update mechanisms
                            cacheWriter(typeName, propertyName);
                        }
                        org.odpi.egeria.connectors.ibm.igc.clientlibrary.model.types.TypeReference type = property.getType();
                        String propertyType = type.getName();
                        if (propertyType.equals("string")) {
                            stringProperties.add(propertyName);
                            nonRelationship.add(propertyName);
                        } else if (propertyType.equals("enum")) {
                            nonRelationship.add(propertyName);
                        } else if (type.getUrl() != null) {
                            if (property.getMaxCardinality() < 0) {
                                pagedRelationship.add(propertyName);
                            }
                        } else {
                            nonRelationship.add(propertyName);
                        }
                        allProperties.add(propertyName);

                        // Instantiate and cache generic property retrieval mechanisms
                        cacheAccessor(typeName, propertyName);
                    }
                }
                typeToAllProperties.put(typeName, allProperties);
                typeToNonRelationshipProperties.put(typeName, nonRelationship);
                typeToStringProperties.put(typeName, stringProperties);
                typeToPagedRelationshipProperties.put(typeName, pagedRelationship);

            }
            typeToDisplayName.put(typeName, typeDetails.getName());

        }

    }

    /**
     * Indicates whether the IGC object type can be created (true) or not (false).
     *
     * @param typeName the name of the IGC object type
     * @return boolean
     */
    public boolean isCreatable(String typeName) {
        cacheTypeDetails(typeName);
        return typesThatCanBeCreated.contains(typeName);
    }

    /**
     * Indicates whether assets of this type include modification details (true) or not (false).
     *
     * @param typeName the name of the IGC asset type for which to check whether it tracks modification details
     * @return boolean
     */
    public boolean hasModificationDetails(String typeName) {
        return typesThatIncludeModificationDetails.contains(typeName);
    }

    /**
     * Retrieve the names of all properties for the provided IGC object type, or null if the type is unknown.
     *
     * @param typeName the name of the IGC object type
     * @return {@code List<String>}
     */
    public List<String> getAllPropertiesForType(String typeName) {
        cacheTypeDetails(typeName);
        return typeToAllProperties.getOrDefault(typeName, null);
    }

    /**
     * Retrieve the names of all non-relationship properties for the provided IGC object type, or null if the type is
     * unknown.
     *
     * @param typeName the name of the IGC object type
     * @return {@code List<String>}
     */
    public List<String> getNonRelationshipPropertiesForType(String typeName) {
        cacheTypeDetails(typeName);
        return typeToNonRelationshipProperties.getOrDefault(typeName, null);
    }

    /**
     * Retrieve the names of all string properties for the provided IGC object type, or null if the type is unknown.
     *
     * @param typeName the name of the IGC object type
     * @return {@code List<String>}
     */
    public List<String> getAllStringPropertiesForType(String typeName) {
        cacheTypeDetails(typeName);
        return typeToStringProperties.getOrDefault(typeName, null);
    }

    /**
     * Retrieve the names of all paged relationship properties for the provided IGC object type, or null if the type is
     * unknown.
     *
     * @param typeName the name of the IGC object type
     * @return {@code List<String>}
     */
    public List<String> getPagedRelationshipPropertiesForType(String typeName) {
        cacheTypeDetails(typeName);
        return typeToPagedRelationshipProperties.getOrDefault(typeName, null);
    }

    /**
     * Register a POJO as an object to handle serde of JSON objects.<br>
     * Note that this MUST be done BEFORE any object mappingRemoved (translation) is done!
     * <br><br>
     * In general, you'll want your POJO to extend at least the model.Reference
     * object in this package; more likely the model.MainObject (for your own OpenIGC object),
     * or if you are adding custom attributes to one of the native asset types, consider
     * directly extending that asset from model.generated.*
     * <br><br>
     * To allow this dynamic registration to work, also ensure you have a @JsonTypeName("...") annotation
     * in your class set to the type that the IGC REST API uses to refer to the asset (eg. for Term.class
     * it would be "term"). See the generated POJOs for examples.
     *
     * @param clazz the Java Class (POJO) object to register
     * @see #getPOJOForType(String)
     */
    public void registerPOJO(Class clazz) {
        JsonTypeName typeName = (JsonTypeName) clazz.getAnnotation(JsonTypeName.class);
        if (typeName != null) {
            String typeId = typeName.value();
            this.mapper.registerSubtypes(clazz);
            this.registeredPojosByType.put(typeId, clazz);
            if (log.isInfoEnabled()) { log.info("Registered IGC type {} to be handled by POJO: {}", typeId, clazz.getCanonicalName()); }
        } else {
            if (log.isErrorEnabled()) { log.error("Unable to find JsonTypeName annotation to identify type in POJO: {}", clazz.getCanonicalName()); }
        }
    }

    /**
     * Returns the POJO that is registered to serde the provided IGC asset type.
     * <br><br>
     * Note that the POJO must first be registered via registerPOJO!
     *
     * @param typeName name of the IGC asset
     * @return Class
     * @see #registerPOJO(Class)
     */
    public Class getPOJOForType(String typeName) {
        return findPOJOForType(typeName);
        //return this.registeredPojosByType.get(typeName);
    }

    /**
     * Retrieve the POJO for the provided IGC REST API's JSON representation into a Java object.
     *
     * @param assetType the IGC REST API's JSON representation
     * @return Class
     */
    public final Class findPOJOForType(String assetType) {
        Class igcPOJO = null;
        StringBuilder sbPojoName = new StringBuilder();
        sbPojoName.append(IGCRestConstants.IGC_REST_BASE_MODEL_PKG);
        sbPojoName.append(".");
        sbPojoName.append(IGCRestConstants.getClassNameForAssetType(assetType));
        try {
            igcPOJO = Class.forName(sbPojoName.toString());
        } catch (ClassNotFoundException e) {
            if (log.isErrorEnabled()) { log.error("Unable to find POJO class: {}", sbPojoName.toString(), e); }
        }
        return igcPOJO;
    }

    /**
     * Returns true iff the workflow is enabled in the environment against which the REST connection is defined.
     *
     * @return Boolean
     */
    public Boolean isWorkflowEnabled() {
        return this.workflowEnabled;
    }

    /**
     * Construct a unique key for dynamic reading and writing of a given type's specific property.
     *
     * @param typeName the name of the IGC object type
     * @param propertyName the name of the property within the object
     * @return String
     */
    private String getDynamicPropertyKey(String typeName, String propertyName) {
        return typeName + "$" + propertyName;
    }

    /**
     * Cache a dynamic property reader to access the specified property for the specified IGC object type.
     *
     * @param type the name of the IGC object type
     * @param property the name of the property within the IGC object type
     */
    private void cacheAccessor(String type, String property) {
        String key = getDynamicPropertyKey(type, property);
        if (!typeAndPropertyToAccessor.containsKey(key)) {
            DynamicPropertyReader reader = new DynamicPropertyReader(getPOJOForType(type), property);
            typeAndPropertyToAccessor.put(key, reader);
        }
    }

    /**
     * Retrieve a dynamic property reader to access properties of the provided asset type, and create one if it does
     * not already exist.
     *
     * @param type the IGC asset type from which to retrieve the property
     * @param property the name of the property to retrieve
     * @return DynamicPropertyReader
     */
    private DynamicPropertyReader getAccessor(String type, String property) {
        cacheAccessor(type, property);
        return typeAndPropertyToAccessor.getOrDefault(getDynamicPropertyKey(type, property), null);
    }

    /**
     * Cache a dynamic property writer to update the specified property for the specified IGC object type.
     *
     * @param type the name of the IGC object type
     * @param property the name of the property within the IGC object type
     */
    private void cacheWriter(String type, String property) {
        String key = getDynamicPropertyKey(type, property);
        if (!typeAndPropertyToWriter.containsKey(key)) {
            DynamicPropertyWriter writer = new DynamicPropertyWriter(getPOJOForType(type), property);
            typeAndPropertyToWriter.put(key, writer);
        }
    }

    /**
     * Retrieve a dynamic property writer to update properties of the provided asset type, and create one if it does
     * not already exist.
     *
     * @param type the IGC asset type for which to update a property
     * @param property the name of the property to update
     * @return DynamicPropertyWriter
     */
    private DynamicPropertyWriter getWriter(String type, String property) {
        cacheWriter(type, property);
        return typeAndPropertyToWriter.getOrDefault(getDynamicPropertyKey(type, property), null);
    }

    /**
     * Retrieve a property of an IGC object based on the property's name.
     *
     * @param object the IGC object from which to retrieve the property's value
     * @param property the name of the property for which to retrieve the value
     * @return Object
     */
    public Object getPropertyByName(Reference object, String property) {
        if (object != null) {
            DynamicPropertyReader accessor = getAccessor(object.getType(), property);
            return accessor.getProperty(object);
        } else {
            return null;
        }
    }

    /**
     * Set a property of an IGC object based on the property's name.
     *
     * @param object the IGC object for which to set the property's value
     * @param property the name of the property to set / update
     * @param value the value to set on the property
     */
    public void setPropertyByName(Reference object, String property, Object value) {
        if (object != null) {
            DynamicPropertyWriter writer = getWriter(object.getType(), property);
            writer.setProperty(object, value);
        }
    }

    /**
     * Ensures that the modification details of the asset are populated (takes no action if already populated or
     * the asset does not support them).
     *
     * @param object the IGC object for which to populate modification details
     * @return boolean indicating whether details were successfully / already populated (true) or not (false)
     */
    public boolean populateModificationDetails(Reference object) {

        boolean success = true;

        if (object != null) {

            // Only bother retrieving the details if the object supports them and they aren't already present
            boolean bHasModificationDetails = hasModificationDetails(object.getType());
            String createdBy = (String) getPropertyByName(object, IGCRestConstants.MOD_CREATED_BY);

            if (bHasModificationDetails && createdBy == null) {

                if (log.isDebugEnabled()) {
                    log.debug("Populating modification details that were missing...");
                }

                IGCSearchCondition idOnly = new IGCSearchCondition("_id", "=", object.getId());
                IGCSearchConditionSet idOnlySet = new IGCSearchConditionSet(idOnly);
                IGCSearch igcSearch = new IGCSearch(object.getType(), idOnlySet);
                igcSearch.addProperties(IGCRestConstants.getModificationProperties());
                igcSearch.setPageSize(2);
                ItemList<Reference> assetsWithModDetails = search(igcSearch);
                success = (!assetsWithModDetails.getItems().isEmpty());
                if (success) {

                    Reference assetWithModDetails = assetsWithModDetails.getItems().get(0);
                    setPropertyByName(object, IGCRestConstants.MOD_CREATED_ON, getPropertyByName(assetWithModDetails, IGCRestConstants.MOD_CREATED_ON));
                    setPropertyByName(object, IGCRestConstants.MOD_CREATED_BY, getPropertyByName(assetWithModDetails, IGCRestConstants.MOD_CREATED_BY));
                    setPropertyByName(object, IGCRestConstants.MOD_MODIFIED_ON, getPropertyByName(assetWithModDetails, IGCRestConstants.MOD_MODIFIED_ON));
                    setPropertyByName(object, IGCRestConstants.MOD_MODIFIED_BY, getPropertyByName(assetWithModDetails, IGCRestConstants.MOD_MODIFIED_BY));

                }
            }
        }

        return success;

    }

    /**
     * Ensures that the _context of the asset is populated (takes no action if already populated).
     * In addition, if the asset type supports them, will also retrieve and set modification details.
     *
     * @param object the IGC object for which to populate the context
     * @return boolean indicating whether _context was successfully / already populated (true) or not (false)
     */
    public boolean populateContext(Reference object) {

        boolean success = true;

        if (object != null) {

            // Only bother retrieving the context if it isn't already present
            List<Reference> ctx = object.getContext();
            if (ctx == null || ctx.isEmpty()) {

                if (log.isDebugEnabled()) {
                    log.debug("Context is empty, populating...");
                }

                boolean bHasModificationDetails = hasModificationDetails(object.getType());

                IGCSearchCondition idOnly = new IGCSearchCondition("_id", "=", object.getId());
                IGCSearchConditionSet idOnlySet = new IGCSearchConditionSet(idOnly);
                IGCSearch igcSearch = new IGCSearch(object.getType(), idOnlySet);
                if (bHasModificationDetails) {
                    igcSearch.addProperties(IGCRestConstants.getModificationProperties());
                }
                igcSearch.setPageSize(2);
                ItemList<Reference> assetsWithCtx = search(igcSearch);
                success = (!assetsWithCtx.getItems().isEmpty());
                if (success) {

                    Reference assetWithCtx = assetsWithCtx.getItems().get(0);
                    object.setContext(assetWithCtx.getContext());

                    if (bHasModificationDetails) {
                        setPropertyByName(object, IGCRestConstants.MOD_CREATED_ON, getPropertyByName(assetWithCtx, IGCRestConstants.MOD_CREATED_ON));
                        setPropertyByName(object, IGCRestConstants.MOD_CREATED_BY, getPropertyByName(assetWithCtx, IGCRestConstants.MOD_CREATED_BY));
                        setPropertyByName(object, IGCRestConstants.MOD_MODIFIED_ON, getPropertyByName(assetWithCtx, IGCRestConstants.MOD_MODIFIED_ON));
                        setPropertyByName(object, IGCRestConstants.MOD_MODIFIED_BY, getPropertyByName(assetWithCtx, IGCRestConstants.MOD_MODIFIED_BY));
                    }

                }

            }
        }

        return success;

    }

}
