package com.drajer.bsa.controller;

import static com.drajer.bsa.controller.ExpectedOutcome.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.springframework.test.context.TestPropertySource;

// The eRSD spec allows specifying action conditions as either CQL or FHIRPath
// This test disables FhirPAth processing to ensure CQL functions as expected
// IOW, CQL alternative expressions should be selected and evaluated.
@RunWith(Parameterized.class)
@TestPropertySource(
    properties = {
      "kar.directory=src/test/resources/Bsa/Scenarios/kars/rulefiltersCqlOnly",
      "fhirpath.enabled=false"
    })
public class RuleFiltersERSDCQLOnlyTest extends BaseKarsTest {
  protected FhirContext fhirContext = FhirContext.forCached(FhirVersionEnum.R4);

  public RuleFiltersERSDCQLOnlyTest(TestCaseInfo testCaseInfo) {
    super(testCaseInfo);
  }

  @Test
  public void test() throws Exception {
    super.testScenarioAndValidate();
  }

  // This generates a list of "TestCaseInfos" that describe the scenario
  // Comment out lines that don't work to temporarily disable test cases.
  // for eCSD tests the main points are
  // 1. Whether or not a reporting Bundle is generated (i.e. initial-pop = true)
  // 2. The Bundle contains a Measure report (TODO: validate that the other
  // resources are present)
  // 3. The MeasureReport has the correct info for the test case
  @Parameters(name = "{0}")
  public static Collection<TestCaseInfo> data() {
    return Arrays.asList(
        //    new TestCaseInfo("PlanDefinition_eRSD_Instance_CqlOnly", "Reportable", REPORTED),
        new TestCaseInfo("PlanDefinition_eRSD_Instance_CqlOnly", "NotTriggered", NOT_TRIGGERED),
        new TestCaseInfo("PlanDefinition_eRSD_Instance_CqlOnly", "Triggered", TRIGGERED_ONLY));
  }
}
