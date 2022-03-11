package com.drajer.bsa.kar.action;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IOperationProcessMsgMode;
import com.drajer.bsa.ehr.service.EhrQueryService;
import com.drajer.bsa.kar.model.BsaAction;
import com.drajer.bsa.model.BsaTypes.BsaActionStatusType;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.bsa.model.PublicHealthAuthority;
import com.drajer.bsa.service.PublicHealthAuthorityService;
import com.drajer.sof.utils.FhirContextInitializer;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.UriType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SubmitReport extends BsaAction {

  private final Logger logger = LoggerFactory.getLogger(SubmitReport.class);
  private static final String R4 = "R4";
  private String submissionEndpoint;

  /** The FHIR Context Initializer necessary to retrieve FHIR resources */
  @Autowired FhirContextInitializer fhirContextInitializer;

  @Autowired PublicHealthAuthorityService publicHealthAuthorityService;

  @Override
  public BsaActionStatus process(KarProcessingData data, EhrQueryService ehrService) {
    logger.info(" Executing the submission of the Report to : {}", submissionEndpoint);

    BsaActionStatus actStatus = new SubmitReportStatus();
    actStatus.setActionId(this.getActionId());
    // Check Timing constraints and handle them before we evaluate conditions.
    BsaActionStatusType status = processTimingData(data);

    if (status != BsaActionStatusType.Scheduled || getIgnoreTimers()) {

      List<DataRequirement> input = getInputData();

      Set<Resource> resourcesToSubmit = new HashSet<>();

      if (input != null) {
        for (DataRequirement dr : input) {
          Set<Resource> resources = data.getOutputDataById(dr.getId());
          resourcesToSubmit.addAll(resources);
        }
      }

      if (!data.getHealthcareSetting().getTrustedThirdParty().isEmpty()) {
        submitResources(
            resourcesToSubmit,
            data,
            ehrService,
            actStatus,
            data.getHealthcareSetting().getTrustedThirdParty());
      } else if (!submissionEndpoint.isEmpty()) {
        submitResources(resourcesToSubmit, data, ehrService, actStatus, submissionEndpoint);
      } else {
        Set<UriType> endpoints = data.getKar().getReceiverAddresses();
        logger.info("Sending data to endpoints {} ", endpoints);
        if (endpoints.size() > 0) {
          for (UriType uri : endpoints) {
            logger.info("Submitting resources to {}", uri);
            submitResources(resourcesToSubmit, data, ehrService, actStatus, uri.getValueAsString());
          }
        }
      }

    } else {

      logger.info(
          " Action may be executed in the future or Conditions have not been met, so cannot proceed any further. ");
      logger.info(" Setting Action Status : {}", status);
      actStatus.setActionStatus(status);
    }

    return actStatus;
  }

  private void submitResources(
      Set<Resource> resourcesToSubmit,
      KarProcessingData data,
      EhrQueryService ehrService,
      BsaActionStatus actStatus,
      String submissionEndpoint) {
    logger.info("SubmitResources called: sending data to {}", submissionEndpoint);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);
    PublicHealthAuthority pha;
    if (submissionEndpoint.endsWith("/")) {
      submissionEndpoint = submissionEndpoint.substring(0, submissionEndpoint.length() - 1);
    }
    pha = publicHealthAuthorityService.getPublicHealthAuthorityByUrl(submissionEndpoint);
    if (pha == null) {
      logger.info("PHA is NULL");
      if (!submissionEndpoint.endsWith("$process-message")) {
        pha =
            publicHealthAuthorityService.getPublicHealthAuthorityByUrl(
                String.format("%s/$process-message", submissionEndpoint));
      }
    }

    String token = "";
    if (pha != null) {
      logger.info(
          "Attempting to retrieve TOKEN from PHA {} or {}", pha.getTokenUrl(), pha.getTokenURL());
      token = ehrService.getToken(pha).getString("access_token");
    } else {
      logger.warn("No PHA was found with submission endpoint {}", submissionEndpoint);
      logger.warn("Continuing without auth token");
    }
    try (InputStream inputStream =
        SubmitReport.class.getClassLoader().getResourceAsStream("report-headers.properties")) {
      Properties headers = new Properties();
      headers.load(inputStream);
      for (Resource r : resourcesToSubmit) {

        IGenericClient client =
            fhirContextInitializer.createClient(context, submissionEndpoint, token);

        context.getRestfulClientFactory().setSocketTimeout(30 * 1000);

        // All submissions are expected to be bundles
        Bundle bundleToSubmit = (Bundle) r;

        IOperationProcessMsgMode<IBaseResource> operation =
            client.operation().processMessage().setMessageBundle(bundleToSubmit);
        headers.forEach(
            (key, value) -> operation.withAdditionalHeader((String) key, (String) value));
        Bundle responseBundle = (Bundle) operation.encodedJson().execute();

        if (responseBundle != null) {
          //          logger.info(
          //              "Response Bundle:::::{}",
          //              context.newJsonParser().encodeResourceToString(responseBundle));

          data.addActionOutput(actionId, responseBundle);

          logger.info(" Adding Response Bundle to output using id {}", responseBundle.getId());

          data.addActionOutputById(responseBundle.getId(), responseBundle);
        } else {
          logger.info("Response BUNDLE IS NULL");
        }

        if (conditionsMet(data)) {

          // Execute sub Actions
          executeSubActions(data, ehrService);

          // Execute Related Actions.
          executeRelatedActions(data, ehrService);
        }

        actStatus.setActionStatus(BsaActionStatusType.Completed);
      }
    } catch (IOException ex) {
      logger.error("Error while loading Action Classes from Proporties File ");
    } // for all resources to be submitted
  }

  private static int count = 1;

  public void saveResourceToFile(Resource res) {

    String fileName =
        "target//output//kars"
            + res.getResourceType().toString()
            + "_"
            + res.getId()
            + "_"
            + count
            + ".json";

    FhirContext context = fhirContextInitializer.getFhirContext(R4);
    String data = context.newJsonParser().encodeResourceToString(res);

    try (DataOutputStream outStream =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {

      logger.info(" Writing data to file: {}", fileName);
      outStream.writeBytes(data);
      count++;
    } catch (IOException e) {
      logger.debug(" Unable to write data to file: {}", fileName, e);
    }
  }

  public String getSubmissionEndpoint() {
    return submissionEndpoint;
  }

  public void setSubmissionEndpoint(String submissionEndpoint) {
    this.submissionEndpoint = submissionEndpoint;
  }
}
