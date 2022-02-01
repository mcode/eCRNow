package com.drajer.bsa.ehr.service.impl;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.drajer.bsa.ehr.service.EhrAuthorizationService;
import com.drajer.bsa.ehr.service.EhrQueryService;
import com.drajer.bsa.model.FhirServerDetails;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.bsa.utils.BsaServiceUtils;
import com.drajer.sof.utils.FhirContextInitializer;
import com.drajer.sof.utils.ResourceUtils;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 *
 * <h1>EhrQueryService</h1>
 *
 * This class defines the implementation methods to access data from the Ehr for a set of resources.
 *
 * @author nbashyam
 */
@Service
@Transactional
public class EhrFhirR4QueryServiceImpl implements EhrQueryService {

  private final Logger logger = LoggerFactory.getLogger(EhrFhirR4QueryServiceImpl.class);

  /** The EHR Authorization Service class enables the BSA to get an access token. */
  @Qualifier("backendauth")
  @Autowired
  EhrAuthorizationService backendAuthorizationService;

  @Qualifier("ehrauth")
  @Autowired
  EhrAuthorizationService ehrAuthorizationService;

  @Qualifier("passwordauth")
  @Autowired
  PasswordAuthorizationServiceImpl passwordAuthorizationService;

  private static final String R4 = "R4";
  private static final String PATIENT_RESOURCE = "Patient";
  private static final String PATIENT_CONTEXT = "patientContext";
  private static final String PATIENT_ID_SEARCH_PARAM = "?patient=";

  /** The FHIR Context Initializer necessary to retrieve FHIR resources */
  @Autowired FhirContextInitializer fhirContextInitializer;

  /**
   * The method is used to retrieve data from the Ehr.
   *
   * @param kd The processing context which contains information such as patient, encounter,
   *     previous data etc.
   * @return The Map of Resources to its type.
   */
  @Override
  public HashMap<ResourceType, Set<Resource>> getFilteredData(
      KarProcessingData kd, HashMap<String, ResourceType> resTypes) {
    JSONObject tokenResponse = getToken(kd.getHealthcareSetting());
    kd.getNotificationContext().setEhrAccessToken(tokenResponse.getString("access_token"));
    kd.getNotificationContext().setEhrAccessTokenExpiryDuration(tokenResponse.getInt("expires_in"));

    Integer expiresInSec = (Integer) tokenResponse.get("expires_in");
    Instant expireInstantTime = new Date().toInstant().plusSeconds(Long.valueOf(expiresInSec));
    kd.getNotificationContext().setEhrAccessTokenExpirationTime(Date.from(expireInstantTime));
    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);

    // Get Patient by Id always
    Resource res =
        getResourceById(
            client, context, PATIENT_RESOURCE, kd.getNotificationContext().getPatientId());
    if (res != null) {

      logger.info(
          " Found Patient resource for Id : {}", kd.getNotificationContext().getPatientId());

      Set<Resource> resources = new HashSet<>();
      resources.add(res);
      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(res.getResourceType(), resources);
      kd.addResourcesByType(resMap);
    }

    // Fetch Resources by Patient Id.
    for (Map.Entry<String, ResourceType> entry : resTypes.entrySet()) {

      logger.info(" Fetching Resource of type {}", entry.getValue());

      // Always true...
      if (entry.getValue() != ResourceType.Patient || entry.getValue() != ResourceType.Encounter) {
        String url =
            kd.getNotificationContext().getFhirServerBaseUrl()
                + "/"
                + entry.getValue().toString()
                + PATIENT_ID_SEARCH_PARAM
                + kd.getNotificationContext().getPatientId();

        logger.info(" Resource Query Url : {}", url);

        getResourcesByPatientId(
            client,
            context,
            entry.getValue().toString(),
            url,
            kd,
            entry.getValue(),
            entry.getKey());
      }
    }

    // Get other resources for Patient
    return kd.getFhirInputData();
  }

  /**
   * The method is used to retrieve data from the Ehr.
   *
   * @param kd The processing context which contains information such as patient, encounter,
   *     previous data etc.
   * @return The Map of Resources to its type.
   */
  @Override
  public HashMap<ResourceType, Set<Resource>> getFilteredData(
      KarProcessingData kd, List<DataRequirement> dRequirements) {
    JSONObject tokenResponse = getToken(kd.getHealthcareSetting());
    kd.getNotificationContext().setEhrAccessToken(tokenResponse.getString("access_token"));
    kd.getNotificationContext().setEhrAccessTokenExpiryDuration(tokenResponse.getInt("expires_in"));

    Integer expiresInSec = (Integer) tokenResponse.get("expires_in");
    Instant expireInstantTime = new Date().toInstant().plusSeconds(Long.valueOf(expiresInSec));
    kd.getNotificationContext().setEhrAccessTokenExpirationTime(Date.from(expireInstantTime));
    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);

    // Get Patient by Id always
    Resource res =
        getResourceById(
            client, context, PATIENT_RESOURCE, kd.getNotificationContext().getPatientId());
    if (res != null) {

      logger.info(
          " Found Patient resource for Id : {}", kd.getNotificationContext().getPatientId());

      Set<Resource> resources = new HashSet<>();
      resources.add(res);
      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(res.getResourceType(), resources);
      kd.addResourcesByType(resMap);
    }

    // Fetch Resources by Patient Id.
    for (DataRequirement entry : dRequirements) {
      String id = entry.getId();
      ResourceType type = ResourceType.valueOf(entry.getType());
      logger.info(" Fetching Resource of type {}", type);

      if (type != ResourceType.Patient || type != ResourceType.Encounter) {
        String url =
            kd.getNotificationContext().getFhirServerBaseUrl()
                + "/"
                + type
                + PATIENT_ID_SEARCH_PARAM
                + kd.getNotificationContext().getPatientId();

        logger.info(" Resource Query Url : {}", url);

        // get the resources
        Set<Resource> resources = fetchResources(client, context, url);
        // filter resources by any filters in the drRequirements
        Set<Resource> filtered = BsaServiceUtils.filterResources(resources, entry, kd);
        // add filtered resources to kd by type and id

        HashMap<ResourceType, Set<Resource>> resMap = new HashMap<ResourceType, Set<Resource>>();
        HashMap<String, Set<Resource>> resMapById = new HashMap<String, Set<Resource>>();

        resMap.put(type, resources);
        resMapById.put(id, resources);
        kd.addResourcesByType(resMap);
        kd.addResourcesById(resMapById);
      }
    }

    // Get other resources for Patient
    return kd.getFhirInputData();
  }

  /**
   * @param kd The data object for getting the healthcareSetting and notification context from
   * @param context The HAPI FHIR context for making a FHIR client with
   * @return
   */
  public IGenericClient getClient(KarProcessingData kd, FhirContext context) {
    JSONObject token = getToken(kd.getHealthcareSetting());

    return fhirContextInitializer.createClient(
        context, kd.getHealthcareSetting().getFhirServerBaseURL(), token.getString("access_token"));
  }

  /**
   * @param kd The KarProcessingData includes data about the fhir server to create a resource on
   * @param resource the resource to create on the fhir server
   */
  public void createResource(KarProcessingData kd, Resource resource) {

    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);
    client.create().resource(resource).execute();
  }

  /**
   * @param kd The KarProcessingData includes data about the fhir server to update a resource on
   * @param resource the resource (with ID) to update on the fhir server
   */
  public void updateResource(KarProcessingData kd, Resource resource) {

    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);
    client.update().resource(resource).execute();
  }

  /**
   * @param kd The KarProcessingData which contains information about the fhir server
   * @param resourceType The resource type of the resource to be deleted
   * @param id The logical ID of the resource to be deleted
   */
  public void deleteResource(KarProcessingData kd, ResourceType resourceType, String id) {

    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    IIdType iIdType = new IdType(id);
    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);
    client.delete().resourceById(resourceType.toString(), id).execute();
  }

  public JSONObject getToken(FhirServerDetails fsd) {
    String password = fsd.getPassword();
    String secret = fsd.getClientSecret();
    if (password != null && !password.isEmpty()) {
      return passwordAuthorizationService.getAuthorizationToken(fsd);
    } else if (secret == null || secret.isEmpty()) {
      return backendAuthorizationService.getAuthorizationToken(fsd);
    } else {
      return ehrAuthorizationService.getAuthorizationToken(fsd);
    }
  }

  public Resource getResourceById(
      IGenericClient genericClient, FhirContext context, String resourceName, String resourceId) {

    Resource resource = null;

    try {

      logger.info("Getting data for Resource : {} with Id : {}", resourceName, resourceId);

      resource =
          (Resource) (genericClient.read().resource(resourceName).withId(resourceId).execute());

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.error(
          "Error in getting {} resource by Id: {}", resourceName, resourceId, responseException);
    } catch (Exception e) {
      logger.error("Error in getting {} resource by Id: {}", resourceName, resourceId, e);
    }
    return resource;
  }

  public Set<Resource> fetchResources(
      IGenericClient genericClient, FhirContext context, String searchUrl) {
    Set<Resource> resources = new HashSet<Resource>();
    Bundle bundle = genericClient.search().byUrl(searchUrl).returnBundle(Bundle.class).execute();
    getAllR4RecordsUsingPagination(genericClient, bundle);
    List<BundleEntryComponent> bc = bundle.getEntry();
    for (BundleEntryComponent comp : bc) {
      resources.add(comp.getResource());
    }
    return resources;
  }

  public void getResourcesByPatientId(
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String searchUrl,
      KarProcessingData kd,
      ResourceType resType,
      String id) {

    logger.info("Invoking search url : {}", searchUrl);
    Set<Resource> resources;
    HashMap<ResourceType, Set<Resource>> resMap;
    HashMap<String, Set<Resource>> resMapById;

    try {
      logger.info(
          "Getting {} data using Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId());

      Bundle bundle = genericClient.search().byUrl(searchUrl).returnBundle(Bundle.class).execute();

      getAllR4RecordsUsingPagination(genericClient, bundle);

      if (bundle != null) {
        logger.info(
            "Total No of Entries {} retrieved : {}", resourceName, bundle.getEntry().size());

        List<BundleEntryComponent> bc = bundle.getEntry();

        if (bc != null) {

          resources = new HashSet<>();
          resMap = new HashMap<>();
          resMapById = new HashMap<>();
          for (BundleEntryComponent comp : bc) {

            logger.info(" Adding Resource Id : {}", comp.getResource().getId());
            resources.add(comp.getResource());
          }
          Set<Resource> uniqueResources =
              ResourceUtils.deduplicate(resources).stream().collect(Collectors.toSet());
          resMap.put(resType, uniqueResources);
          resMapById.put(id, uniqueResources);
          kd.addResourcesByType(resMap);
          kd.addResourcesById(resMapById);

          logger.info(" Adding {} resources of type : {}", uniqueResources.size(), resType);
        } else {
          logger.error(" No entries found for type : {}", resType);
        }
      } else {
        logger.error(" Unable to retrieve resources for type : {}", resType);
      }

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.info(
          "Error in getting {} resource by Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId(),
          responseException);
    } catch (Exception e) {
      logger.info(
          "Error in getting {} resource by Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId(),
          e);
    }
  }

  private void getAllR4RecordsUsingPagination(IGenericClient genericClient, Bundle bundle) {

    if (bundle.hasEntry()) {
      List<BundleEntryComponent> entriesList = bundle.getEntry();
      if (bundle.hasLink() && bundle.getLink(IBaseBundle.LINK_NEXT) != null) {
        logger.info(
            "Found Next Page in Bundle :{}", bundle.getLink(IBaseBundle.LINK_NEXT).getUrl());
        Bundle nextPageBundleResults = genericClient.loadPage().next(bundle).execute();
        entriesList.addAll(nextPageBundleResults.getEntry());
        nextPageBundleResults.setEntry(entriesList);
        getAllR4RecordsUsingPagination(genericClient, nextPageBundleResults);
      }
    }
  }
}
