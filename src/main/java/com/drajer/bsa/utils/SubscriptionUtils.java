package com.drajer.bsa.utils;

import com.drajer.bsa.model.NotificationContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 * <h1>SubscriptionUtils</h1>
 *
 * The class provides utility methods for processing subscriptions.
 *
 * @author nbashyam
 */
public class SubscriptionUtils {

  private static final String TOPIC_PARAMETER = "topic";
  private static final String SUBSCRIPTION_PARAMETER = "subscription";
  private static final String TYPE_PARAMETER = "type";
  private static final String STATUS_PARAMETER = "status";
  private static final String EVENT_NOTIFICATION_CODE = "event-notification";
  private static final String SUBSCRIPTION_URL_SPLIT_REGEX = "/Subscription";
  private static final int FHIR_SERVER_URL_MIN_LENGTH = 8;

  private static HashMap<String, String> topicToNamedEventMap = new HashMap<>();
  private static HashMap<String, String> namedEventToResourceMap = new HashMap<>();

  private static final Logger logger = LoggerFactory.getLogger(SubscriptionUtils.class);

  // Load the Topic to Named Event Map.
  static {
    try (InputStream input =
        SubscriptionUtils.class
            .getClassLoader()
            .getResourceAsStream("subscription-topic-named-event.properties")) {

      Properties prop = new Properties();
      prop.load(input);

      prop.forEach((key, value) -> topicToNamedEventMap.put((String) key, (String) value));

    } catch (IOException ex) {
      logger.error("Error while loading Topic to Named Event Mapping from Proporties File ");
    }
  }

  // Load the Named Event to Resource Map.
  static {
    try (InputStream input =
        SubscriptionUtils.class
            .getClassLoader()
            .getResourceAsStream("named-event-resource.properties")) {

      Properties prop = new Properties();
      prop.load(input);

      prop.forEach((key, value) -> namedEventToResourceMap.put((String) key, (String) value));

    } catch (IOException ex) {
      logger.error("Error while loading Named Event to Resource Mapping from Proporties File ");
    }
  }

  public static NotificationContext getNotificationContext(
      Bundle bundle, HttpServletRequest request, HttpServletResponse response) {

    NotificationContext nc = null;

    // Check that the bundle is the right type
    // Has a Parameters resource as the first entry
    // Has a full resource which caused the notification to occur by checking that there is at least
    // 2 entries.
    if (bundle != null
        && (bundle.getType() == BundleType.HISTORY)
        && bundle.getEntryFirstRep() != null
        && (bundle.getEntryFirstRep().getResource() instanceof Parameters)
        && bundle.getEntry().size() >= 2) {

      logger.info(" The notification passes the first level of checks for processing ");

      // Ensure that the Parameters resource can be retrieved first.
      BundleEntryComponent bec = bundle.getEntryFirstRep();

      Parameters p = (Parameters) bec.getResource();

      CodeType subsType =
          ((p.getParameter(TYPE_PARAMETER) instanceof CodeType)
              ? (CodeType) p.getParameter(TYPE_PARAMETER)
              : null);
      Reference subsRef =
          ((p.getParameter(SUBSCRIPTION_PARAMETER) instanceof Reference)
              ? (Reference) p.getParameter(SUBSCRIPTION_PARAMETER)
              : null);
      CanonicalType subTopic =
          ((p.getParameter(TOPIC_PARAMETER) instanceof CanonicalType)
              ? (CanonicalType) p.getParameter(TOPIC_PARAMETER)
              : null);

      if (subsType != null
          && (subsType.getCode().equals(EVENT_NOTIFICATION_CODE))
          && subsRef != null
          && subTopic != null
          && (topicToNamedEventMap.containsKey(subTopic.getValue()))) {

        String namedEvent = topicToNamedEventMap.get(subTopic.getValue());
        String resourceType = namedEventToResourceMap.get(namedEvent);

        logger.info(" Named Event Received : {}", namedEvent);
        logger.info(" ResourceType Expected : {}", resourceType);

        // Verify if the resource received matches the resource Type expected.
        Resource res = bundle.getEntry().get(1).getResource();

        logger.info(" Resource Type Received : {}", res.getResourceType().toString());

        if (res.getResourceType().toString().equals(resourceType)) {

          logger.info(
              " Found the Resource Type Expected in the bundle : {}",
              res.getResourceType().toString());

          String fhirServerUrl = getFhirServerUrl(subsRef);

          // Check if we received a FHIR Server URL.
          if (fhirServerUrl != null && (fhirServerUrl.length() > FHIR_SERVER_URL_MIN_LENGTH)) {

            nc = new NotificationContext();
            nc.setTriggerEvent(namedEvent);
            nc.setFhirServerBaseUrl(fhirServerUrl);
            nc.setPatientId(getPatientId(res));
            nc.setNotificationResourceId(res.getIdElement().getIdPart());
            nc.setNotificationResourceType(resourceType);
            nc.setLastUpdated(Date.from(Instant.now()));
            nc.setNotifiedResource(res);

            String xRequestId = request.getHeader("X-Request-ID");
            String xCorrelationId = request.getHeader("X-Correlation-ID");

            if (xRequestId != null
                && xRequestId.length() > 0
                && xCorrelationId != null
                && xCorrelationId.length() > 0) {

              // Setup the attributes to be different.
              nc.setxRequestId(xRequestId);
              nc.setxCorrelationId(xCorrelationId);
            } else if (xRequestId != null && xRequestId.length() > 0) {
              // Set both to the same since the other one  is null.
              nc.setxRequestId(xRequestId);
              nc.setxCorrelationId(xRequestId);
            } else if (xCorrelationId != null && xCorrelationId.length() > 0) {
              // Set both to the same since the other one  is null.

              nc.setxCorrelationId(xCorrelationId);
              nc.setxRequestId(xCorrelationId);
            } else {
              String guid = java.util.UUID.randomUUID().toString();
              nc.setxRequestId(guid);
              nc.setxCorrelationId(guid);
            }

          } else {

            logger.error(
                "Fhir Server Url received is not valid for further processing, Url Value : {}",
                (fhirServerUrl != null) ? fhirServerUrl : "Null Value");
          }

        } else {

          logger.error(
              " Resource Type Received {}, does not match Resource Type Expected {}",
              res.getResourceType().getDeclaringClass().toString(),
              ResourceType.fromCode(resourceType).getDeclaringClass().toString());
        }

      } else {

        logger.error(" Bundle does not pass the necessary checks for processing. ");
      }

    } else {

      logger.error(" Bundle does not have necessary data to process the notification. ");
    }

    return nc;
  }

  public static String getFhirServerUrl(Reference ref) {

    // This checks to see if the URL contains the "Subscription" keyword.
    // https://example.org/fhir/r4/Subscription/encounter-close
    if (ref != null
        && ref.getReference() != null
        && ref.getReference().contains(ResourceType.Subscription.toString())) {

      // Split the String based on the String "Subscription" to obtain the FHIR Base Url.
      logger.info(" Subscription Url : {}", ref.getReference());

      String[] fhirServerUrl = ref.getReference().split(SUBSCRIPTION_URL_SPLIT_REGEX);

      if (fhirServerUrl != null && fhirServerUrl.length >= 1) {

        logger.info(" Found the Fhir Server Url ");
        return fhirServerUrl[0];
      }

    } else {

      logger.error(
          " Subscription Notification Reference does not have the appropriate Subscription URL required ");
    }

    return null;
  }

  public static String getPatientId(Resource res) {

    if (res != null) {

      if (res instanceof Encounter) {

        Encounter en = (Encounter) res;

        if (en.getSubject() != null && en.getSubject().getReferenceElement() != null)
          return en.getSubject().getReferenceElement().getIdPart();
      }

      logger.error(" Need to add other resources here after connectathon ");
    }

    return null;
  }
}
