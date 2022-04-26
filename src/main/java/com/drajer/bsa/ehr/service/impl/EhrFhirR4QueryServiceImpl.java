package com.drajer.bsa.ehr.service.impl;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.drajer.bsa.ehr.service.EhrAuthorizationService;
import com.drajer.bsa.ehr.service.EhrQueryService;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.bsa.utils.BsaServiceUtils;
import com.drajer.sof.utils.FhirContextInitializer;
import com.drajer.sof.utils.ResourceUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Encounter.EncounterLocationComponent;
import org.hl7.fhir.r4.model.Encounter.EncounterParticipantComponent;
import org.hl7.fhir.instance.model.api.IIdType;
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

      Set<Resource> resources = new HashSet<Resource>();
      resources.add(res);
      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(res.getResourceType(), resources);
      kd.addResourcesByType(resMap);
    }

    if (kd.getNotificationContext()
        .getNotificationResourceType()
        .equals(ResourceType.Encounter.toString())) {

      logger.info(
          " Fetch Encounter resource for Id : {} ",
          kd.getNotificationContext().getNotificationResourceId());

      Resource enc =
          getResourceById(
              client,
              context,
              ResourceType.Encounter.toString(),
              kd.getNotificationContext().getNotificationResourceId());

      if (enc != null) {

        logger.info(
            " Found Encounter resource for Id : {}",
            kd.getNotificationContext().getNotificationResourceId());

        Set<Resource> resources = new HashSet<Resource>();
        resources.add(enc);
        HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
        resMap.put(enc.getResourceType(), resources);
        kd.addResourcesByType(resMap);
      }
    }

    // Fetch Resources by Patient Id.
    for (Map.Entry<String, ResourceType> entry : resTypes.entrySet()) {

      logger.info(" Fetching Resource of type {}", entry.getValue());

      if (entry.getValue() != ResourceType.Patient && entry.getValue() != ResourceType.Encounter) {
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

      if (type != ResourceType.Patient && type != ResourceType.Encounter) {
        String url =
                kd.getNotificationContext().getFhirServerBaseUrl()
                        + "/"
                        + type
                        + PATIENT_ID_SEARCH_PARAM
                        + kd.getNotificationContext().getPatientId();

        logger.info(" Resource Query Url : {}", url);

        // get the resources
        Set<Resource> resources = kd.getResourcesByType(type.toString());
        if (resources == null || resources.size() == 0) {
          resources = fetchResources(client, context, url);
          kd.addResourcesByType(type, resources);
        }
        // filter resources by any filters in the drRequirements
        Set<Resource> filtered = BsaServiceUtils.filterResources(resources, entry, kd);
        // add filtered resources to kd by type and id
        logger.info("Filtered resource count of type {} dr_id {} is {}", type, id, filtered.size());
        kd.addResourcesById(id, filtered);
      } else {
        kd.addResourcesById(id, kd.getResourcesByType(type.toString()));
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
    getToken(kd,context);
    return fhirContextInitializer.createClient(
        context,
        kd.getHealthcareSetting().getFhirServerBaseURL(),
        kd.getNotificationContext().getEhrAccessToken(),
        kd.getNotificationContext().getxRequestId());
  }

  public void getToken(KarProcessingData kd, FhirContext context){
    String secret = kd.getHealthcareSetting().getClientSecret();
    if (secret == null || secret.isEmpty()) {
      backendAuthorizationService.getAuthorizationToken(kd);
    } else {
      ehrAuthorizationService.getAuthorizationToken(kd);
    }

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

  public HashMap<ResourceType, Set<Resource>> loadJurisdicationData(KarProcessingData kd) {



    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client =getClient(kd,context);

    // Retrieve the encounter
    Set<Resource> res = kd.getResourcesByType(ResourceType.Encounter.toString());

    Set<Resource> practitioners = new HashSet<Resource>();
    Set<Resource> locations = new HashSet<Resource>();
    Set<Resource> organizations = new HashSet<Resource>();
    Map<String, String> practitionerMap = new HashMap<>();

    for (Resource r : res) {

      Encounter encounter = (Encounter) r;

      // Load Practitioners
      if (encounter.getParticipant() != null) {

        List<EncounterParticipantComponent> participants = encounter.getParticipant();

        for (EncounterParticipantComponent participant : participants) {
          if (participant.getIndividual() != null) {
            Reference practitionerReference = participant.getIndividual();
            String practitionerID = practitionerReference.getReferenceElement().getIdPart();
            if (!practitionerMap.containsKey(practitionerID)) {
              Practitioner practitioner =
                  (Practitioner)
                      getResourceById(
                          client, context, ResourceType.Practitioner.toString(), practitionerID);
              if (practitioner != null && !practitionerMap.containsKey(practitionerID)) {
                practitioners.add(practitioner);
                practitionerMap.put(practitionerID, ResourceType.Practitioner.toString());
              }
            }
          } // Individual != null
        } // For all participants
      } // For participant != null

      // Load Organizations
      if (Boolean.TRUE.equals(encounter.hasServiceProvider())) {
        Reference organizationReference = encounter.getServiceProvider();
        if (organizationReference.hasReferenceElement()) {
          Organization organization =
              (Organization)
                  getResourceById(
                      client,
                      context,
                      "Organization",
                      organizationReference.getReferenceElement().getIdPart());
          if (organization != null) {
            organizations.add(organization);
          }
        }
      }

      // Load Locations
      if (Boolean.TRUE.equals(encounter.hasLocation())) {
        List<Location> locationList = new ArrayList<>();
        List<EncounterLocationComponent> enocunterLocations = encounter.getLocation();
        for (EncounterLocationComponent location : enocunterLocations) {
          if (location.getLocation() != null) {
            Reference locationReference = location.getLocation();
            Location locationResource =
                (Location)
                    getResourceById(
                        client,
                        context,
                        "Location",
                        locationReference.getReferenceElement().getIdPart());
            if (locationResource != null) {
              locations.add(locationResource);
            }
          }
        }
      }
    } // for all encounters

    if (practitioners.size() > 0) {

      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(ResourceType.Practitioner, practitioners);
      kd.addResourcesByType(resMap);
    }

    if (locations.size() > 0) {

      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(ResourceType.Location, locations);
      kd.addResourcesByType(resMap);
    }

    if (organizations.size() > 0) {

      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(ResourceType.Organization, organizations);
      kd.addResourcesByType(resMap);
    }

    return kd.getFhirInputData();
  }

  @Override
  public Resource getResourceById(KarProcessingData kd, String resourceName, String resourceId) {


    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);

    return getResourceById(client, context, resourceName, resourceId);
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
    Set<Resource> resources = null;
    HashMap<ResourceType, Set<Resource>> resMap = null;
    HashMap<String, Set<Resource>> resMapById = null;

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

          resources = new HashSet<Resource>();
          resMap = new HashMap<>();
          resMapById = new HashMap<>();
          for (BundleEntryComponent comp : bc) {

            logger.debug(" Adding Resource Id : {}", comp.getResource().getId());
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
