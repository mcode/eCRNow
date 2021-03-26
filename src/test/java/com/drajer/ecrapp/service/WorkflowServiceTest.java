package com.drajer.ecrapp.service;

import ca.uhn.fhir.context.FhirContext;
import com.drajer.eca.model.EventTypes.WorkflowEvent;
import com.drajer.ecrapp.config.SpringConfiguration;
import com.drajer.sof.dao.impl.LaunchDetailsDaoImpl;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.model.R4FhirData;
import com.drajer.sof.service.LoadingQueryService;
import com.drajer.sof.service.TriggerQueryService;
import com.drajer.test.util.TestUtils;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = SpringConfiguration.class)
@AutoConfigureTestDatabase
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = "ersd.file.location=src/test/resources/AppData/ersd.json")
public class WorkflowServiceTest {
  private static final Logger logger = LoggerFactory.getLogger(WorkflowServiceTest.class);

  private LaunchDetails launchDetails;

  private Bundle loadingQueryBundle;

  private Bundle triggerQueryBundle;

  @MockBean private TriggerQueryService triggerQueryService;

  @MockBean private LoadingQueryService loadingQueryService;

  private static FhirContext fhirContext = FhirContext.forR4();

  @Autowired LaunchDetailsDaoImpl launchDetailsDaoImpl;

  @Autowired WorkflowService workflowService;

  @Before
  public void init() {
    MockitoAnnotations.initMocks(this);
    setUpMockData();
    launchDetails =
        (LaunchDetails)
            TestUtils.getResourceAsObject(
                "R4/Misc/LaunchDetails/LaunchDetails_Workflow_service.json", LaunchDetails.class);

    // triggerQueryService = PowerMockito.mock(TriggerQueryR4Bundle.class);
    // loadingQueryService = PowerMockito.mock(LoadingQueryR4Bundle.class);
  }

  private void setUpMockData() {
    String loadingQueryData =
        TestUtils.getFileContentAsString("R4/Bundle/LoadingQueryR4Bundle.json");
    loadingQueryBundle = (Bundle) fhirContext.newJsonParser().parseResource(loadingQueryData);

    String triggerQueryData =
        TestUtils.getFileContentAsString("R4/Bundle/TriggerQueryR4Bundle.json");
    triggerQueryBundle = (Bundle) fhirContext.newJsonParser().parseResource(triggerQueryData);
  }

  @Test
  public void testWorkflowService() throws JsonParseException, JsonMappingException, IOException {
    LaunchDetails lD = launchDetailsDaoImpl.saveOrUpdate(launchDetails);
    logger.info("LaunchDetailsSaved:::::::::::::::::::::::" + lD.getId());

    // Mock Trigger Queries Data
    R4FhirData triggerQueryFhirData = new R4FhirData();
    triggerQueryFhirData.setData(triggerQueryBundle);
    Mockito.when(
            triggerQueryService.getData(
                launchDetails, launchDetails.getStartDate(), launchDetails.getEndDate()))
        .thenReturn(triggerQueryFhirData);

    // Mock Loading Queries Data
    R4FhirData loadingQueryFhirData = new R4FhirData();
    loadingQueryFhirData.setData(loadingQueryBundle);
    Mockito.when(
            loadingQueryService.getData(
                launchDetails, launchDetails.getStartDate(), launchDetails.getEndDate()))
        .thenReturn(loadingQueryFhirData);

    workflowService.executeEicrWorkflow(launchDetails, WorkflowEvent.SOF_LAUNCH);
    waitForEICR(50000);
  }

  private void waitForEICR(int interval) {
    try {
      Thread.sleep(interval);
    } catch (InterruptedException e) {
      logger.warn("Issue with thread sleep", e);
      Thread.currentThread().interrupt();
    }
  }
}
