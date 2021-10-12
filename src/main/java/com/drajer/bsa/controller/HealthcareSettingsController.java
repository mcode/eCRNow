package com.drajer.bsa.controller;

import com.drajer.bsa.model.HealthcareSetting;
import com.drajer.bsa.service.HealthcareSettingsService;
import java.util.List;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 *
 * <h1>HealthcareSettingsController</h1>
 *
 * The HealthcareSettingsController is used to manage the different healthcare settings that will be
 * using the Backend Service App.(BSA). For each Healthcare Settings a set of configuration items
 * are initially provided by the administrator configuring the BSA. These configuration items
 * include FHIR Server URL, Name, Knowledge Artifacts to be processed etc.
 *
 * @author nbashyam
 * @since 2021-04-15
 */
@RestController
public class HealthcareSettingsController {

  @Autowired HealthcareSettingsService healthcareSettingsService;

  private final Logger logger = LoggerFactory.getLogger(HealthcareSettingsController.class);

  /**
   * This method is used to retrieve the HealthcareSettings details by primary key of the table. The
   * user interface for the BSA is expected to use this method when it already knows the id of the
   * HealthcareSetting.
   *
   * @param hsId The id to be used to retrieve the HealthcareSetting
   * @return The HealthcareSetting object for the id provided
   */
  @CrossOrigin
  @RequestMapping("/api/healthcareSettings/{hsId}")
  public HealthcareSetting getHealthcareSettingById(@PathVariable("hsId") Integer hsId) {
    return healthcareSettingsService.getHealthcareSettingById(hsId);
  }

  /**
   * This method is used to create a HealthcareSettings object by providing the necessary details.
   * The BSA administrator is expected to use this method when provisioning each of the
   * HealthcareSettings.
   *
   * @param hsDetails The HealthcareSettings details passed as part of the Request Body.
   * @return This returns the HTTP Response Entity containing the JSON representation of the
   *     HealthcareSetting when successful, else returns appropriate error.
   */
  @CrossOrigin
  @RequestMapping(value = "/api/healthcareSettings", method = RequestMethod.POST)
  public ResponseEntity<?> createHealthcareSettings(@RequestBody HealthcareSetting hsDetails) {
    HealthcareSetting hsd =
        healthcareSettingsService.getHealthcareSettingByUrl(hsDetails.getFhirServerBaseURL());

    if (hsd == null) {

      logger.info("Healthcare Setting does not exist, Saving the Healthcare Settings");

      healthcareSettingsService.saveOrUpdate(hsDetails);

      return new ResponseEntity<>(hsDetails, HttpStatus.OK);

    } else {

      logger.error("FHIR Server URL is already registered, suggest modifying the existing record.");

      JSONObject responseObject = new JSONObject();
      responseObject.put("status", "error");
      responseObject.put(
          "message",
          "FHIR Server URL is already registered, suggest modifying the existing record. is already registered");
      return new ResponseEntity<>(responseObject, HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * This method is used to update a HealthcareSettings object by providing the necessary details.
   * The BSA administrator is expected to use this method when updating already provisioned
   * HealthcareSetting.
   *
   * @param hsDetails The updated HealthcareSettings details passed as part of the Request Body.
   * @return This returns the HTTP Response Entity containing the JSON representation of the
   *     HealthcareSetting when successful, else returns appropriate error.
   */
  @CrossOrigin
  @RequestMapping(value = "/api/healthcareSettings", method = RequestMethod.PUT)
  public ResponseEntity<?> updateHealthcareSettings(@RequestBody HealthcareSetting hsDetails) {
    HealthcareSetting existingHsd =
        healthcareSettingsService.getHealthcareSettingByUrl(hsDetails.getFhirServerBaseURL());

    if (existingHsd == null || (existingHsd.getId().equals(hsDetails.getId()))) {
      logger.info("Saving the Client Details");
      healthcareSettingsService.saveOrUpdate(hsDetails);
      return new ResponseEntity<>(hsDetails, HttpStatus.OK);
    } else {
      logger.error(
          "Healthcare Setting is already registered with a different Id which is {}, contact developer. ",
          existingHsd.getId());
      JSONObject responseObject = new JSONObject();
      responseObject.put("status", "error");
      responseObject.put(
          "message",
          "Healthcare Setting is already registered with a different Id, contact developer. ");
      return new ResponseEntity<>(responseObject, HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * This method is used to retrieve the HealthcareSettings details by the FHIR Server URL for the
   * setting. The user interface for the BSA is expected to use this method during configuration of
   * the HealthcareSetting.
   *
   * @param url The url to be used to retrieve the HealthcareSetting
   * @return The HealthcareSetting for the url provided
   */
  @CrossOrigin
  @RequestMapping("/api/healthcareSettings")
  public HealthcareSetting getHealthcareSettingsByUrl(@RequestParam(value = "url") String url) {
    return healthcareSettingsService.getHealthcareSettingByUrl(url);
  }

  /**
   * This method is used to retrieve all existing HealthcareSettings details. The user interface for
   * the BSA is expected to use this method during configuration of the HealthcareSetting.
   *
   * @return The existing list of HealthcareSettings.
   */
  @CrossOrigin
  @RequestMapping("/api/healthcareSettings/")
  public List<HealthcareSetting> getAllHealthcareSettings() {
    return healthcareSettingsService.getAllHealthcareSettings();
  }
}
