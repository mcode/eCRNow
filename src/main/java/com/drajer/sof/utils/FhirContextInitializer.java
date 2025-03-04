package com.drajer.sof.utils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.dstu2.resource.Bundle;
import ca.uhn.fhir.model.dstu2.resource.Bundle.Entry;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.drajer.sof.model.LaunchDetails;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FhirContextInitializer {

  private static final String DSTU2 = "DSTU2";
  private static final String DSTU2_1 = "DSTU2_1";
  private static final String DSTU3 = "DSTU3";
  private static final String R4 = "R4";
  private static final String QUERY_PATIENT = "?patient=";

  private static final Logger logger = LoggerFactory.getLogger(FhirContextInitializer.class);

  /**
   * Get FhirContext appropriate to fhirVersion
   *
   * @param fhirVersion The FHIR Version to use, either as a fhir version or a package name.
   * @return The appropriate FhirContext to use for the server
   */
  public FhirContext getFhirContext(String fhirVersion) {
    switch (fhirVersion) {
      case DSTU2:
        return FhirContext.forDstu2();
      case DSTU2_1:
        return FhirContext.forDstu2_1();
      case DSTU3:
        return FhirContext.forDstu3();
      case R4:
        return FhirContext.forR4();
      default:
        return FhirContext.forDstu2();
    }
  }

  /**
   * Creates a GenericClient with standard intercepters used throughout the services.
   *
   * @param url the base URL of the FHIR server to connect to
   * @param accessToken the name of the key to use to generate the token
   * @return a Generic Client
   */
  public IGenericClient createClient(FhirContext context, String url, String accessToken) {
    logger.info("Initializing the Client");
    IGenericClient client = context.newRestfulGenericClient(url);
    context.getRestfulClientFactory().setSocketTimeout(30 * 1000);
    client.registerInterceptor(new BearerTokenAuthInterceptor(accessToken));
    if (logger.isDebugEnabled()) {
      client.registerInterceptor(new LoggingInterceptor(true));
    }
    logger.info("Initialized the Client");
    return client;
  }

  public MethodOutcome submitResource(IGenericClient genericClient, Resource resource) {
    MethodOutcome outcome = new MethodOutcome();
    try {
      outcome = genericClient.create().resource(resource).prettyPrint().encodedJson().execute();
    } catch (Exception e) {
      logger.error(
          "Error in Submitting the resource::::: {}", resource.getResourceType().name(), e);
    }

    return outcome;
  }

  public IBaseResource getResouceById(
      LaunchDetails authDetails,
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String resourceId) {
    IBaseResource resource = null;
    try {
      logger.info("Getting {} data", resourceName);
      resource = genericClient.read().resource(resourceName).withId(resourceId).execute();
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

  public IBaseBundle getResourceByPatientId(
      LaunchDetails authDetails,
      IGenericClient genericClient,
      FhirContext context,
      String resourceName) {
    IBaseBundle bundleResponse = null;
    String url =
        authDetails.getEhrServerURL()
            + "/"
            + resourceName
            + QUERY_PATIENT
            + authDetails.getLaunchPatientId();
    bundleResponse = getResourceBundleByUrl(authDetails, genericClient, context, resourceName, url);
    return bundleResponse;
  }

  protected IBaseBundle getObservationByPatientId(
      LaunchDetails authDetails,
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String category) {
    IBaseBundle bundleResponse = null;
    String url =
        authDetails.getEhrServerURL()
            + "/"
            + resourceName
            + QUERY_PATIENT
            + authDetails.getLaunchPatientId()
            + "&category="
            + category;
    bundleResponse = getResourceBundleByUrl(authDetails, genericClient, context, resourceName, url);
    return bundleResponse;
  }

  protected IBaseBundle getResourceByPatientIdAndCode(
      LaunchDetails authDetails,
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String code,
      String system) {
    IBaseBundle bundleResponse = null;
    String url =
        authDetails.getEhrServerURL()
            + "/"
            + resourceName
            + QUERY_PATIENT
            + authDetails.getLaunchPatientId()
            + "&code="
            + system
            + "|"
            + code;
    bundleResponse = getResourceBundleByUrl(authDetails, genericClient, context, resourceName, url);
    return bundleResponse;
  }

  public static IBaseBundle getResourceBundleByUrl(
      LaunchDetails authDetails,
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String url) {
    logger.info("Invoking url::::::::::::::: {}", url);
    IBaseBundle bundleResponse = null;
    try {
      logger.info(
          "Getting {} data using Patient Id: {}", resourceName, authDetails.getLaunchPatientId());
      if (authDetails.getFhirVersion().equalsIgnoreCase(DSTU2)) {
        Bundle bundle = genericClient.search().byUrl(url).returnBundle(Bundle.class).execute();
        getAllDSTU2RecordsUsingPagination(genericClient, bundle);
        if (logger.isInfoEnabled()) {
          logger.info(
              "Total No of {} received::::::::::::::::: {}",
              resourceName,
              bundle.getEntry().size());
        }
        bundleResponse = bundle;
      } else if (authDetails.getFhirVersion().equalsIgnoreCase(R4)) {
        org.hl7.fhir.r4.model.Bundle bundle =
            genericClient
                .search()
                .byUrl(url)
                .returnBundle(org.hl7.fhir.r4.model.Bundle.class)
                .execute();
        getAllR4RecordsUsingPagination(genericClient, bundle);
        if (logger.isInfoEnabled()) {
          logger.info(
              "Total No of {} received::::::::::::::::: {}",
              resourceName,
              bundle.getEntry().size());
        }
        bundleResponse = bundle;
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
          authDetails.getLaunchPatientId(),
          responseException);
    } catch (Exception e) {
      logger.info(
          "Error in getting {} resource by Patient Id: {}",
          resourceName,
          authDetails.getLaunchPatientId(),
          e);
    }

    return bundleResponse;
  }

  private static void getAllR4RecordsUsingPagination(
      IGenericClient genericClient, org.hl7.fhir.r4.model.Bundle bundle) {
    if (bundle.hasEntry()) {
      List<BundleEntryComponent> entriesList = bundle.getEntry();
      if (bundle.hasLink() && bundle.getLink(IBaseBundle.LINK_NEXT) != null) {
        logger.info(
            "Found Next Page in Bundle:::::{}", bundle.getLink(IBaseBundle.LINK_NEXT).getUrl());
        org.hl7.fhir.r4.model.Bundle nextPageBundleResults =
            genericClient.loadPage().next(bundle).execute();
        entriesList.addAll(nextPageBundleResults.getEntry());
        nextPageBundleResults.setEntry(entriesList);
        getAllR4RecordsUsingPagination(genericClient, nextPageBundleResults);
      }
    }
  }

  private static void getAllDSTU2RecordsUsingPagination(
      IGenericClient genericClient, Bundle bundle) {
    if (bundle.getEntry() != null) {
      List<Entry> entriesList = bundle.getEntry();
      if (bundle.getLink(IBaseBundle.LINK_NEXT) != null) {
        logger.info(
            "Found Next Page in Bundle:::::{}", bundle.getLink(IBaseBundle.LINK_NEXT).getUrl());
        Bundle nextPageBundleResults = genericClient.loadPage().next(bundle).execute();
        entriesList.addAll(nextPageBundleResults.getEntry());
        nextPageBundleResults.setEntry(entriesList);
        getAllDSTU2RecordsUsingPagination(genericClient, nextPageBundleResults);
      }
    }
  }
}
