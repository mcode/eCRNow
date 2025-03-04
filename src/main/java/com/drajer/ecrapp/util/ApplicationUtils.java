package com.drajer.ecrapp.util;

import ca.uhn.fhir.model.dstu2.composite.CodeableConceptDt;
import ca.uhn.fhir.model.dstu2.composite.CodingDt;
import ca.uhn.fhir.parser.IParser;
import com.drajer.eca.model.PatientExecutionState;
import com.drajer.eca.model.TimingSchedule;
import com.drajer.ecrapp.config.ValueSetSingleton;
import com.drajer.ecrapp.service.PlanDefinitionProcessor;
import com.drajer.sof.model.LaunchDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.UsageContext;
import org.hl7.fhir.r4.model.ValueSet;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ApplicationUtils {

  @Autowired
  @Qualifier("jsonParser")
  IParser jsonParser;

  public ApplicationUtils() {}

  private static final Logger logger = LoggerFactory.getLogger(ApplicationUtils.class);

  public static List<CanonicalType> getValueSetListFromGrouper(String grouperId) {

    List<CanonicalType> valueSetIdList = null;

    for (ValueSet valueset : ValueSetSingleton.getInstance().getGrouperValueSets()) {

      logger.info(" Looking for grouper value set for {}", grouperId);

      if (valueset.getId().equals(grouperId) || grouperId.contains(valueset.getId())) {

        logger.info(" Found Grouper Value Set for {}", grouperId);

        if (valueset.getCompose() != null && valueset.getCompose().getInclude() != null) {

          logger.info(" Value Set is composed of other value sets ");
          List<ConceptSetComponent> csc = valueset.getCompose().getInclude();

          for (ConceptSetComponent conceptSetComponent : csc) {

            logger.info(" Adding Value Set Ids to the list ");

            valueSetIdList = conceptSetComponent.getValueSet();
          }

          if (valueSetIdList != null && !valueSetIdList.isEmpty()) {
            logger.info(" Value Set Id List Size = {}", valueSetIdList.size());
          } else {
            logger.info(" Value Set Id List is NULL");
          }
        }
        break;
      } else {
        logger.info(" Value Set Id {}  does not match grouper Id ", valueset.getId());
      }
    }
    return valueSetIdList;
  }

  public static ValueSet getValueSetGrouperFromId(String grouperId) {

    ValueSet valueSetGrouper = null;

    for (ValueSet valueset : ValueSetSingleton.getInstance().getGrouperValueSets()) {

      if (valueset.getId().equals(grouperId) || grouperId.contains(valueset.getId())) {

        logger.info(" Grouper Id {}", grouperId);
        valueSetGrouper = valueset;
      }
    }
    return valueSetGrouper;
  }

  public static Set<ValueSet> getValueSetByIds(List<CanonicalType> valueSetIdList) {

    Set<ValueSet> valueSets = new HashSet<>();

    if (Optional.ofNullable(valueSetIdList).isPresent()) {

      logger.info(" Value Set id List is not null ");

      for (CanonicalType canonicalType : valueSetIdList) {

        for (ValueSet valueSet : ValueSetSingleton.getInstance().getValueSets()) {

          if (valueSet.getId().equalsIgnoreCase(canonicalType.getValueAsString())
              || ((valueSet.getUrl() != null)
                  && (valueSet.getUrl().equalsIgnoreCase(canonicalType.getValueAsString())))) {
            valueSets.add(valueSet);
            break;
          }
        }

        for (ValueSet valueSet : ValueSetSingleton.getInstance().getCovidValueSets()) {

          if (valueSet.getId().equalsIgnoreCase(canonicalType.getValueAsString())
              || ((valueSet.getUrl() != null)
                  && (valueSet.getUrl().equalsIgnoreCase(canonicalType.getValueAsString())))) {
            valueSets.add(valueSet);
            break;
          }
        }
      }
    }
    return valueSets;
  }

  public static Set<ValueSet> getCovidValueSetByIds(List<CanonicalType> valueSetIdList) {

    Set<ValueSet> valueSets = new HashSet<>();

    if (Optional.ofNullable(valueSetIdList).isPresent()) {

      for (CanonicalType canonicalType : valueSetIdList) {

        logger.info(" Checking Value set {}", canonicalType.getValueAsString());

        for (ValueSet valueSet : ValueSetSingleton.getInstance().getValueSets()) {

          if (valueSet.getId().equalsIgnoreCase(canonicalType.getValueAsString())
              && isACovidValueSet(valueSet)) {
            logger.info(" Found a Covid Value Set for Grouper using Id {}", valueSet.getId());
            valueSets.add(valueSet);
            break;
          } else if ((valueSet.getUrl() != null)
              && (valueSet.getUrl().equalsIgnoreCase(canonicalType.getValueAsString()))
              && isACovidValueSet(valueSet)) {

            logger.info("Urls Matched for a Covid Value Set {}", valueSet.getId());
            valueSets.add(valueSet);
            break;
          }
        }

        for (ValueSet valueSet : ValueSetSingleton.getInstance().getCovidValueSets()) {

          if (valueSet.getId().equalsIgnoreCase(canonicalType.getValueAsString())
              && isACovidValueSet(valueSet)) {

            logger.info(" Found a Covid Value Set for Grouper using Id {}", valueSet.getId());
            valueSets.add(valueSet);
            break;
          } else if ((valueSet.getUrl() != null)
              && (valueSet.getUrl().equalsIgnoreCase(canonicalType.getValueAsString()))
              && isACovidValueSet(valueSet)) {
            logger.info("Urls Matched for a Covid Value Set {}", valueSet.getId());
            valueSets.add(valueSet);
            break;
          }
        }
      }
    }

    return valueSets;
  }

  public static Set<String> convertValueSetsToString(Set<ValueSet> valuesets) {

    Set<String> retVal = new HashSet<>();
    ValueSetExpansionComponent valueSetExpansionComponent;
    List<ValueSetExpansionContainsComponent> valueSetExpansionContainsComponentList;

    if (valuesets != null && !valuesets.isEmpty()) {

      for (ValueSet vs : valuesets) {

        logger.info(" Value Set Id = {}", vs.getId());
        valueSetExpansionComponent = vs.getExpansion();
        valueSetExpansionContainsComponentList = valueSetExpansionComponent.getContains();

        for (ValueSetExpansionContainsComponent vscomp : valueSetExpansionContainsComponentList) {

          if (vscomp.getSystem() != null && vscomp.getCode() != null) {

            retVal.add(vscomp.getSystem() + "|" + vscomp.getCode());
          }
        }
      }
    }

    return retVal;
  }

  public static Set<String> convertCodeableConceptsToString(List<CodeableConceptDt> codes) {

    Set<String> retVal = new HashSet<>();

    if (codes != null && !codes.isEmpty()) {

      for (CodeableConceptDt cd : codes) {

        List<CodingDt> cds = cd.getCoding();

        for (CodingDt code : cds) {

          if (code.getSystem() != null && code.getCode() != null) {

            retVal.add(code.getSystem() + "|" + code.getCode());
          }
        }
      }
    }

    return retVal;
  }

  public static Set<String> convertR4CodeableConceptsToString(List<CodeableConcept> codes) {

    Set<String> retVal = new HashSet<>();

    if (codes != null && !codes.isEmpty()) {

      for (CodeableConcept cd : codes) {

        List<Coding> cds = cd.getCoding();

        for (Coding code : cds) {

          if (code.getSystem() != null && code.getCode() != null) {

            retVal.add(code.getSystem() + "|" + code.getCode());
          }
        }
      }
    }

    return retVal;
  }

  public static Instant convertTimingScheduleToInstant(TimingSchedule ts, Date timeRef) {

    Instant t = null;

    Duration d = new Duration();

    if (ts.getDurationUnit() != null) {

      logger.debug("Found Duration for Timing Schedule");
      logger.debug(" Duration before conversion = {}", ts.getDuration());
      logger.debug(" Duration Unit = {}", ts.getDurationUnit());
      d.setValue(ts.getDuration());
      d.setUnit(ts.getDurationUnit().toCode());
      logger.debug(" Duration during conversion = {}", d.getValue());
      logger.debug(" Duration Unit = {}", d.getUnit());

    } else if (ts.getFrequencyPeriodUnit() != null) {

      logger.debug("Found Frequency for Timing Schedule ");
      d.setValue(ts.getFrequencyPeriod());
      d.setUnit(ts.getFrequencyPeriodUnit().toCode());
    } else {

      d.setValue(0);
      d.setUnit("s");
    }

    t = convertDurationToInstant(d);

    return t;
  }

  public static Instant convertDurationToInstant(Duration d) {

    Instant t = null;
    String unit = "";

    if (d.getCode() != null) {
      unit = d.getCode();
    } else if (d.getUnit() != null) {
      unit = d.getUnit();
    }

    if (d != null) {

      if (unit.equalsIgnoreCase("a")) {

        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, d.getValue().intValue());
        t = c.toInstant();

      } else if (unit.equalsIgnoreCase("mo")) {

        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, d.getValue().intValue());
        t = c.toInstant();

      } else if (unit.equalsIgnoreCase("wk")) {

        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, (d.getValue().intValue() * 7));
        t = c.toInstant();

      } else if (unit.equalsIgnoreCase("d")) {

        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, d.getValue().intValue());
        t = c.toInstant();

      } else if (unit.equalsIgnoreCase("h")) {

        t = new Date().toInstant().plusSeconds(d.getValue().longValue() * 60 * 60);
      } else if (unit.equalsIgnoreCase("min")) {

        t = new Date().toInstant().plusSeconds(d.getValue().longValue() * 60);
      } else if (unit.equalsIgnoreCase("s")) {

        t = new Date().toInstant().plusSeconds(d.getValue().longValue());
      } else if (d.getValue() != null) {

        t = new Date().toInstant().plusSeconds(d.getValue().longValue());
      } else {
        t = null;
      }
    }

    return t;
  }

  public static void saveDataToFile(String data, String fileName) {

    try (DataOutputStream outStream =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileName)))) {

      logger.info(" Writing data to file: {}", fileName);
      outStream.writeBytes(data);
    } catch (IOException e) {
      logger.debug(" Unable to write data to file: {}", fileName, e);
    }
  }

  public static boolean isACovidValueSet(ValueSet v) {

    boolean retVal = false;

    if (v != null) {

      logger.info(" Checking Value Set Id {}", v.getId());

      if (v.getUseContextFirstRep() != null) {

        UsageContext uc = v.getUseContextFirstRep();

        if (uc.getValue() != null && (uc.getValue() instanceof CodeableConcept)) {

          CodeableConcept cc = (CodeableConcept) uc.getValue();

          if (cc.getCodingFirstRep() != null
              && (cc.getCodingFirstRep().getCode() != null
                  && cc.getCodingFirstRep()
                      .getCode()
                      .contentEquals(PlanDefinitionProcessor.COVID_SNOMED_USE_CONTEXT_CODE))) {
            logger.info(" Found COVID VALUE SET = {}", v.getId());
            retVal = true;
          }
        }
      }
    }

    return retVal;
  }

  public static boolean isAGrouperValueSet(ValueSet v) {

    boolean retVal = false;

    if (v != null) {

      if (v.getUseContextFirstRep() != null) {

        logger.info(" Found Use Context ");

        UsageContext uc = v.getUseContextFirstRep();

        if (uc.getValue() != null && (uc.getValue() instanceof Reference)) {

          logger.info(" Found Use Context Reference ");

          Reference rr = (Reference) uc.getValue();

          if (rr != null
              && rr.getReference() != null
              && (rr.getReference().contains(PlanDefinitionProcessor.GROUPER_VALUE_SET_REFERENCE_1)
                  || rr.getReference()
                      .contains(PlanDefinitionProcessor.GROUPER_VALUE_SET_REFERENCE_2))) {

            logger.info(" Found Grouper VALUE SET = {}", v.getId());
            retVal = true;
          }
        }
      }
    }

    return retVal;
  }

  public static PatientExecutionState getDetailStatus(LaunchDetails details) {

    ObjectMapper mapper = new ObjectMapper();
    PatientExecutionState state = null;

    try {

      state = mapper.readValue(details.getStatus(), PatientExecutionState.class);

    } catch (JsonProcessingException e1) {
      String msg = "Unable to read/write execution state";
      logger.error(msg, e1);
      throw new RuntimeException(msg, e1);
    }

    return state;
  }

  public Bundle readBundleFromFile(String filename) {

    logger.info("About to read File {}", filename);
    Bundle bundle = null;
    try (InputStream in = new FileInputStream(new File(filename))) {
      logger.info("Reading File ");

      bundle = jsonParser.parseResource(Bundle.class, in);
      logger.info("Completed Reading File");
    } catch (Exception e) {
      logger.error("Exception Reading File", e);
    }
    return bundle;
  }

  public IBaseResource readResourceFromFile(String filename) {

    logger.info("About to read File {}", filename);
    IBaseResource resource = null;
    try (InputStream in = new FileInputStream(new File(filename))) {
      logger.info("Reading File ");

      resource = jsonParser.parseResource(in);
      logger.info("Completed Reading File");
    } catch (Exception e) {
      logger.error("Exception Reading File", e);
    }
    return resource;
  }

  public Bundle readBundleFromString(String str) {

    logger.info("About to read str ");
    Bundle bundle = null;
    try {
      logger.info("Reading String ");

      bundle = (Bundle) jsonParser.parseResource(str);

      logger.info("Completed Reading String");
    } catch (Exception e) {
      logger.error("Exception Reading File", e);
    }
    return bundle;
  }
}
