package com.drajer.test;

import com.drajer.sof.model.LaunchDetails;
import com.drajer.test.util.WireMockHelper;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class ITWorkflowServiceTest extends BaseIntegrationTest {

  private String testCaseId;
  private String launchDetailsFile;

  public ITWorkflowServiceTest(String testCaseId, String launchDetails) {
    this.testCaseId = testCaseId;
    this.launchDetailsFile = launchDetails;
  }

  private LaunchDetails launchDetails;

  WireMockHelper stubHelper;

  private static final Logger logger = LoggerFactory.getLogger(ITWorkflowServiceTest.class);
}
