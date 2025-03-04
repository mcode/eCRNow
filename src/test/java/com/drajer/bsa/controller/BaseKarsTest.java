package com.drajer.bsa.controller;

import static com.drajer.bsa.controller.ExpectedOutcome.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleUtil;
import com.drajer.bsa.dao.HealthcareSettingsDao;
import com.drajer.bsa.model.HealthcareSetting;
import com.drajer.bsa.scheduler.ScheduleJobConfiguration;
import com.drajer.bsa.service.SubscriptionNotificationReceiver;
import com.drajer.ecrapp.util.ApplicationUtils;
import com.drajer.test.BaseIntegrationTest;
import com.drajer.test.util.WireMockHelper;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.MeasureReport.MeasureReportGroupPopulationComponent;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@ContextConfiguration(classes = ScheduleJobConfiguration.class)
@TestPropertySource(
    properties = {
      "ignore.timers=true",
      "report-submission.endpoint=http://localhost:9010/fhir",
      "report-validator.endpoint="
    })
public class BaseKarsTest extends BaseIntegrationTest {
  protected FhirContext fhirContext = FhirContext.forCached(FhirVersionEnum.R4);

  protected TestCaseInfo testCaseInfo;

  public BaseKarsTest(TestCaseInfo testCaseInfo) {
    this.testCaseInfo = testCaseInfo;
  }

  private static Logger logger = LoggerFactory.getLogger(BaseKarsTest.class);

  @Autowired protected SubscriptionNotificationReceiver notificationReceiver;

  @Autowired protected ApplicationUtils ap;

  @Autowired protected HealthcareSettingsDao hsDao;

  protected ClassLoader classLoader = getClass().getClassLoader();

  protected WireMockHelper stubHelper;

  @Before
  public void setupNotificationMocking() throws IOException {
    this.wireMockServer.resetAll();
    stubHelper = new WireMockHelper(wireMockServer, wireMockHttpPort);
    logger.info("Creating WireMock stubs..");
    stubHelper.mockFhirRead("/fhir/metadata", getCapabilityStatement());
    logger.info("Set up Test: {}", this.testCaseInfo.getName());
    setupHealthCareSettings();
    mockAccessToken();
    mockScenarioFolder(
        new File(
            classLoader
                .getResource(
                    "Bsa/Scenarios/"
                        + this.testCaseInfo.getPlanDef()
                        + "/"
                        + this.testCaseInfo.getName())
                .getPath()));
  }

  public void testScenarioAndValidate() throws InterruptedException {
    logger.info("Executing Test: {}", this.testCaseInfo.getName());
    try {
      deleteOutputFiles();
      Bundle bundle =
          getNotificationBundle(this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName());
      this.stubHelper.mockProcessMessageBundle(bundle);

      notificationReceiver.processNotification(
          bundle, mock(HttpServletRequest.class), mock(HttpServletResponse.class));

      // TODO: We need a processing complete signal that's not the file output.
      // Some tests should be trigger, but never generate a report.
      // This just waits a "reasonable" amount of time for processing to complete before
      // continuing on
      int loops = 0;
      while (loops < 60) {
        if (reportBundleGenerated(this.testCaseInfo.getName(), this.testCaseInfo.getPlanDef())) {
          break;
        } else {
          Thread.sleep(1000);
          loops++;
        }
      }

      Boolean reportBundleGenerated =
          this.reportBundleGenerated(this.testCaseInfo.getName(), this.testCaseInfo.getPlanDef());

      if (!reportBundleGenerated && this.testCaseInfo.getExpectedOutcome() == REPORTED) {
        throw new RuntimeException(
            String.format(
                "test case %s/%s timed out or did not generate a report when it should have generated a report.",
                this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName()));
      }

      if (reportBundleGenerated && this.testCaseInfo.getExpectedOutcome() == TRIGGERED_ONLY) {
        throw new RuntimeException(
            String.format(
                "test case %s/%s generated a report when it should not have reported.",
                this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName()));
      }

      if (reportBundleGenerated && this.testCaseInfo.getExpectedOutcome() == NOT_TRIGGERED) {
        throw new RuntimeException(
            String.format(
                "test case %s/%s generated a report when it should not have triggered.",
                this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName()));
      }

      // Check measure reports if the expected outcome is trigger or reported and the initialpop is
      // not null,
      // IOW, if this is an eCSD test that should have generated a MeasureReport
      if ((this.testCaseInfo.getExpectedOutcome() == TRIGGERED_ONLY
              || this.testCaseInfo.getExpectedOutcome() == REPORTED)
          && this.testCaseInfo.getInitialPopulation() != null) {
        MeasureReport report = this.getMeasureReport();
        validatePopulation(report, "initial-population", this.testCaseInfo.getInitialPopulation());

        if (this.testCaseInfo.getDenominator() != null) {
          validatePopulation(report, "denominator", this.testCaseInfo.getDenominator());
        }

        // TODO: Denom exclusion?
        if (this.testCaseInfo.getNumerator() != null) {
          validatePopulation(report, "numerator", this.testCaseInfo.getNumerator());
        }
      }

      // If this is an eCSD test, ensure the Bundle has a MeasureReport
      if (this.testCaseInfo.getExpectedOutcome() == REPORTED) {
        Bundle eICR = this.getEicrBundle(this.testCaseInfo.getPlanDef());
        validateBundle(eICR, this.testCaseInfo.getInitialPopulation() != null);
      }
    } catch (Exception e) {
      logger.error(
          String.format(
              "Test %s/%s failed with: %s",
              this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName(), e.getMessage()),
          e);

      throw e;
    }

    logger.info(
        "Test {}/{} succeeded", this.testCaseInfo.getPlanDef(), this.testCaseInfo.getName());
  }

  File getMeasureReportFile() {
    return new File("target/output/karsMeasureReport_null.json");
  }

  File getEICRFile(String planDef) {
    return new File("target/output/karsBundle_eicr-report-" + planDef + ".json");
  }

  File getNotificationBundleFile(String patientId) {
    return new File("target/output/karsBundle_" + patientId + "-notification-bundle.json");
  }

  protected Boolean measureReportGenerated() {
    return this.getMeasureReportFile().exists();
  }

  protected Boolean reportBundleGenerated(String patientId, String planDef) {
    File eICRFile = this.getEICRFile(planDef);
    if (eICRFile.exists()) {
      return true;
    }

    String processMessageUrl = "/fhir/$process-message";
    List<LoggedRequest> requests =
        wireMockServer.findAll(postRequestedFor(urlEqualTo(processMessageUrl)));

    if (requests.size() > 0) {
      return true;
    }

    return false;
  }

  Bundle getEicrBundle(String planDef) {
    File eICRReport = new File("target/output/karsBundle_eicr-report-" + planDef + ".json");

    if (eICRReport.exists()) {
      try (FileInputStream fis = new FileInputStream(eICRReport)) {
        return (Bundle) this.fhirContext.newJsonParser().parseResource(fis);
      } catch (Exception e) {
        return null;
      }
    } else {
      String processMessageUrl = "/fhir/$process-message";
      List<LoggedRequest> requests =
          wireMockServer.findAll(postRequestedFor(urlEqualTo(processMessageUrl)));

      if (requests.size() > 0) {
        try {
          IBaseResource resource =
              FhirContext.forCached(FhirVersionEnum.R4)
                  .newJsonParser()
                  .parseResource(requests.get(0).getBodyAsString());
          if (resource instanceof Bundle) {
            return (Bundle) resource;
          }

        } catch (Exception e) {
          logger.error("Error parsing $process-message request body");
          return null;
        }
      }
    }

    return null;
  }

  MeasureReport getMeasureReport() {
    File mr = new File("target/output/karsMeasureReport_null.json");

    if (mr.exists()) {
      try (FileInputStream fis = new FileInputStream(mr)) {
        return (MeasureReport) this.fhirContext.newJsonParser().parseResource(fis);
      } catch (Exception e) {
        return null;
      }
    }

    return null;
  }

  protected void deleteOutputFiles() {
    File rootFile = new File("target/output");
    File[] files = rootFile.listFiles((FilenameFilter) new WildcardFileFilter("kars*.json"));

    for (File f : files) {
      if (f.exists()) {
        f.delete();
      }
    }
  }

  private void mockScenarioFolder(File scenario) {
    if (scenario.isFile()) {
      throw new IllegalArgumentException(String.format("The scenario must be a folder: %s"));
    }

    Map<String, List<IBaseResource>> resourceMap = readScenarioFolder(scenario);
    if (!resourceMap.containsKey("Patient") || resourceMap.get("Patient").isEmpty()) {
      throw new IllegalArgumentException(
          String.format("No patient resource found for scenario: %s", scenario.getName()));
    }

    if (resourceMap.get("Patient").size() > 1) {
      throw new IllegalArgumentException(
          String.format("Multiple patient resources found for scenario: %s", scenario.getName()));
    }

    String patientId = resourceMap.get("Patient").get(0).getIdElement().getIdPart();

    for (Map.Entry<String, List<IBaseResource>> entry : resourceMap.entrySet()) {
      // Mock a search for all resources of a given type.
      String mockQueryString = String.format("/fhir/%s?patient=%s", entry.getKey(), patientId);
      stubHelper.mockFhirSearch(mockQueryString, entry.getValue());
      for (IBaseResource r : entry.getValue()) {
        // Mock a read for a specific instance of a resource
        String id = r.getIdElement().getIdPart();
        String queryString = String.format("/fhir/%s/%s", entry.getKey(), id);
        stubHelper.mockFhirRead(queryString, r);
      }
    }
  }

  private Map<String, List<IBaseResource>> readScenarioFolder(File scenario) {
    // TODO: Pass in the correct version
    IParser parser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser();

    HashMap<String, List<IBaseResource>> resources = new HashMap<>();

    // Filter subdirectories for now..
    List<File> files =
        Arrays.asList(scenario.listFiles())
            .stream()
            .filter(x -> x.isFile())
            .collect(Collectors.toList());

    for (File f : files) {
      try {
        IBaseResource r = parser.parseResource(new FileInputStream(f));
        List<IBaseResource> resourceList =
            resources.computeIfAbsent(r.fhirType(), k -> new ArrayList<>());
        resourceList.add(r);
      } catch (Exception e) {
        logger.warn("Error reading resource: {}", f.getName());
      }
    }

    return resources;
  }

  private void setupHealthCareSettings() {
    String healthCareSettings = "Bsa/HealthCareSettings.json";
    File healthCareSettingsFile = new File(classLoader.getResource(healthCareSettings).getFile());
    HealthcareSetting hcs = null;
    try {
      hcs = mapper.readValue(healthCareSettingsFile, HealthcareSetting.class);
    } catch (JsonParseException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (JsonMappingException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    if (hcs == null) {
      logger.debug("Health care settings not found: " + healthCareSettings);
      return;
    }

    HealthcareSetting existing = hsDao.getHealthcareSettingByUrl(hcs.getFhirServerBaseURL());
    if (existing != null) {
      logger.debug("Found existing healthcare settings");
      return;
    }

    hsDao.saveOrUpdate(hcs);
  }

  private void mockAccessToken() {
    String accessToken = "cb81ec9fa7d7605a060ffc756fc7d130";
    String expireTime = "3600";
    stubHelper.mockTokenResponse(
        "/token",
        String.format(
            "{ \"access_token\": \"%s\", \n\"expires_in\": \"%s\" }", accessToken, expireTime));
  }

  private Bundle getNotificationBundle(String planDef, String testCase) {
    java.net.URL url =
        DiabetesECSDTest.class
            .getClassLoader()
            .getResource("Bsa/Scenarios/" + planDef + "/" + testCase);
    File rootFile = new File(url.getPath());

    File[] bundles =
        rootFile.listFiles((FilenameFilter) new WildcardFileFilter("*notification-bundle.json"));
    if (bundles == null || bundles.length == 0) {
      throw new RuntimeException(
          "Did not find a notification bundle for test case: " + this.testCaseInfo.getName());
    }

    if (bundles.length > 1) {
      throw new RuntimeException(
          "Found multiple notification bundles for test case: " + this.testCaseInfo.getName());
    }

    String absolutePath = bundles[0].getAbsolutePath();
    return ap.readBundleFromFile(absolutePath);
  }

  public CapabilityStatement getCapabilityStatement() {
    CapabilityStatement metadata = new CapabilityStatement();
    metadata.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
    return metadata;
  }

  protected void validatePopulation(MeasureReport report, String population, int count) {
    Optional<MeasureReportGroupPopulationComponent> pgc =
        report
            .getGroup()
            .get(0)
            .getPopulation()
            .stream()
            .filter(x -> x.getCode().getCodingFirstRep().getCode().equals(population))
            .findFirst();
    if (!pgc.isPresent()) {
      throw new RuntimeException(String.format("MeasureReport missing {} population", population));
    }

    assertEquals(String.format("population: \"%s\"", population), count, pgc.get().getCount());
  }

  protected void validateBundle(Bundle bundle, Boolean shouldHaveMeasureReport) {
    if (shouldHaveMeasureReport) {
      List<MeasureReport> mrs =
          BundleUtil.toListOfResourcesOfType(this.fhirContext, bundle, MeasureReport.class);
      assertEquals("Did not find expected MeasureReport", 1, mrs.size());
    }
  }
}
