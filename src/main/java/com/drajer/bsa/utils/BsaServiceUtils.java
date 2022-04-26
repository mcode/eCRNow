package com.drajer.bsa.utils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.IParser;
import com.drajer.bsa.kar.action.BsaActionStatus;
import com.drajer.bsa.model.BsaTypes;
import com.drajer.bsa.model.BsaTypes.MessageType;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.eca.model.MatchedTriggerCodes;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.DocumentReference.DocumentReferenceContentComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 *
 *
 * <h1>BsaServiceUtils</h1>
 *
 * This class implements a few utilities to be used by many of the different services.
 *
 * @author nbashyam
 */
@Service
public class BsaServiceUtils {

  private static final Logger logger = LoggerFactory.getLogger(BsaServiceUtils.class);

  @Autowired
  @Qualifier("jsonParser")
  IParser jsonParser;

  @Value("${bsa.output.directory}")
  String debugDirectory;

  @Autowired(required = false)
  Map<String, BsaTypes.BsaActionStatusType> actions;

  @Autowired(required = false)
  Map<String, Bundle> eicrBundles;

  private static final String FHIR_PATH_VARIABLE_PREFIX = "%";
  private static IFhirPath FHIR_PATH = new FhirPathR4(FhirContext.forR4());


  public static String getFhirPathVariableString(String id) {

    if (id.length() > 2) {

      String part1 = id.substring(0, 1).toLowerCase();
      String part2 = id.substring(1);

      String result = FHIR_PATH_VARIABLE_PREFIX + part1 + part2;

      return result;
    }

    return id.toLowerCase();
  }

  public Bundle readKarFromFile(String filePath) {

    logger.info("About to read KAR File {}", filePath);
    Bundle bundle = null;
    try (InputStream in = new FileInputStream(new File(filePath))) {
      logger.info("Start Reading KAR File ");

      bundle = jsonParser.parseResource(Bundle.class, in);
      logger.info("Completed Reading KAR File");
    } catch (Exception e) {
      logger.error("Exception Reading KAR File", e);
    }
    return bundle;
  }

  public static Pair<Boolean, MatchedTriggerCodes> isCodeableConceptPresentInValueSet(
      ValueSet vs, CodeableConcept cd, String path, Boolean valElem) {

    Pair<Boolean, MatchedTriggerCodes> retVal = null;
    Boolean matchFound = false;
    MatchedTriggerCodes mtc = null;

    if (cd != null && cd.getCoding().size() > 0) {

      for (Coding c : cd.getCoding()) {

        Pair<Boolean, Pair<String, String>> retInfo = isCodingPresentInValueSet(vs, c);

        if (retInfo != null) {

          logger.info(" Match Found for code {} | {}", retInfo.getValue0(), retInfo.getValue1());

          if (mtc == null) {
            mtc = new MatchedTriggerCodes();
            mtc.setValueSet(vs.getUrl());
            mtc.setValueSetVersion(vs.getVersion());
            mtc.setMatchedPath(path);
            matchFound = true;

            if (valElem) {
              logger.info(" Matched Code is part of a Value Element ");
              mtc.addValue(retInfo.getValue1().getValue0() + "|" + retInfo.getValue1().getValue1());
            } else {
              logger.info(" Matched Code is part of a Code Element ");
              mtc.addCode(retInfo.getValue1().getValue0() + "|" + retInfo.getValue1().getValue1());
            }
          }
        }
      }
    }

    if (matchFound) {
      retVal = new Pair<Boolean, MatchedTriggerCodes>(true, mtc);
    }

    return retVal;
  }

  public static Set<Resource> filterResources(
          Set<Resource> resources, DataRequirement dataRequirement, KarProcessingData kd) {

    List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters =
            dataRequirement.getCodeFilter();
    List<DataRequirement.DataRequirementDateFilterComponent> dateFilters =
            dataRequirement.getDateFilter();

    Set<Resource> filtered = filterByCodeFilters(resources, codeFilters, kd);
    filtered = filterByDateFilters(filtered, dateFilters, kd);
    // gather all codes that
    return filtered;
  }


  public static Set<Resource> filterByCodeFilters(
          Set<Resource> resources,
          List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters,
          KarProcessingData kd) {
    Set<Resource> filtered = new HashSet<Resource>();
    for (Resource res : resources) {
      boolean matches = true;
      for (DataRequirement.DataRequirementCodeFilterComponent drcfc : codeFilters) {
        if (!matchesCodeFilter(res, drcfc, kd)) {
          matches = false;
          break;
        }
      }
      if (matches) {
        logger.info("Resource matches filter {}", res.getId());
        filtered.add(res);
      }
    }
    return filtered;
  }

  public static Set<Resource> filterByDateFilters(
          Set<Resource> resources,
          List<DataRequirement.DataRequirementDateFilterComponent> dateFilters,
          KarProcessingData kd) {
    Set<Resource> filtered = new HashSet<Resource>();
    for (Resource res : resources) {
      boolean matches = true;
      for (DataRequirement.DataRequirementDateFilterComponent drdfc : dateFilters) {
        if (!matchesDateFilter(res, drdfc, kd)) {
          matches = false;
          break;
        }
      }
      if (matches) {
        filtered.add(res);
      }
    }
    return filtered;
  }

  public static boolean matchesCodeFilter(
          Resource resource,
          DataRequirement.DataRequirementCodeFilterComponent codeFilter,
          KarProcessingData kd) {
    // find the attribute by the path element in the code filter: this may be a list of codes or
    // codableconcepts
    // if the filter is contains a valueset match against that
    // if the filter containts codes match against them -- at this sage the matches are ORs.  If the
    // vs or
    // any of the codes match its a match.
    // dont know what this will return
    List<IBase> search = FHIR_PATH.evaluate(resource, codeFilter.getPath(), IBase.class);
    if (search == null || search.size() == 0) {
      return false;
    }

    boolean retVal = false;

    for (IBase ib : search) {
      if (codeFilter.hasValueSet()) {
        if (matchesValueSet(ib, codeFilter.getValueSet(), kd)) {
          retVal = true;
          break;
        }
      }
      if (codeFilter.hasCode()) {
        if (matchesCodes(ib, codeFilter.getCode(), kd)) {
          retVal = true;
          break;
        }
      }
    }
    return retVal;
  }

  public static boolean matchesValueSet(IBase ib, String url, KarProcessingData kd) {
    ValueSet vs = (ValueSet) kd.getKar().getDependentResource(ResourceType.ValueSet, url);
    if (ib instanceof Coding) {
      Coding coding = (Coding) ib;
      return isCodePresentInValueSet(vs, coding.getSystem(), coding.getCode());
    } else if (ib instanceof CodeableConcept) {
      return isCodeableConceptPresentInValueSet(vs, (CodeableConcept) ib);
    }
    return false;
  }

  public static boolean matchesCodes(IBase ib, List<Coding> codes, KarProcessingData kd) {
    if (ib instanceof Coding) {
      Coding ibc = (Coding) ib;
      return codes
              .stream()
              .anyMatch(
                      coding ->
                              ibc.getSystem().equals(coding.getSystem())
                                      && ibc.getCode().equals(coding.getCode()));
    }
    if (ib instanceof CodeableConcept) {
      CodeableConcept ibc = (CodeableConcept) ib;
      List<Coding> ibcCodings = ibc.getCoding();
      return ibcCodings
              .stream()
              .anyMatch(
                      ibcCoding ->
                              codes
                                      .stream()
                                      .anyMatch(
                                              coding ->
                                                      ibcCoding.getSystem().equals(coding.getSystem())
                                                              && ibcCoding.getCode().equals(coding.getCode())));
    }
    return false;
  }

  public static boolean matchesDateFilter(
          Resource r, DataRequirement.DataRequirementDateFilterComponent drdfc, KarProcessingData kd) {
    return true;
  }

  public static Set<String> getMatchableCodes(CodeableConcept cc) {

    Set<String> mtcs = new HashSet<String>();

    if (cc != null && cc.getCoding().size() > 0) {

      for (Coding c : cc.getCoding()) {

        if (c.getSystem() != null && c.getCode() != null) {

          mtcs.add(c.getSystem() + "|" + c.getCode());
        }
      }
    }

    return mtcs;
  }

  public static Boolean isCodeableConceptPresentInValueSet(ValueSet vs, CodeableConcept cc) {
    return cc.getCoding()
            .stream()
            .anyMatch(coding -> isCodePresentInValueSet(vs, coding.getSystem(), coding.getCode()));
  }

  public static Pair<Boolean, Pair<String, String>> isCodingPresentInValueSet(
      ValueSet vs, Coding coding) {

    Pair<Boolean, Pair<String, String>> retVal = null;

    if (coding != null && isCodePresentInValueSet(vs, coding.getSystem(), coding.getCode())) {
      Pair<String, String> matchedCodeInfo = new Pair<>(coding.getSystem(), coding.getCode());
      retVal = new Pair<Boolean, Pair<String, String>>(true, matchedCodeInfo);
    }

    return retVal;
  }

  public static Boolean isCodePresentInValueSet(ValueSet vs, String system, String code) {

    Boolean retVal = false;

    if (vs.hasCompose()) {

      ValueSetComposeComponent vsc = vs.getCompose();

      List<ConceptSetComponent> cscs = vsc.getInclude();

      if (cscs != null) {

        for (ConceptSetComponent csc : cscs) {

          if (csc.getSystem() != null && csc.getSystem().contentEquals(system)) {

            logger.info(" Found Code System {} in value set ", system);

            List<ConceptReferenceComponent> crcs = csc.getConcept();

            if (crcs != null) {

              for (ConceptReferenceComponent crc : crcs) {

                if (crc.getCode().contentEquals(code)) {
                  logger.info(" Found code system {} and code {} in value set ", system, code);
                  retVal = true;
                  break;
                }
              }
            }
          }
        }
      }
    }

    if (!retVal && vs.hasExpansion()) {

      ValueSetExpansionComponent vsec = vs.getExpansion();

      if (vsec.hasContains()) {

        List<ValueSetExpansionContainsComponent> expansion = vsec.getContains();

        for (ValueSetExpansionContainsComponent vsecc : expansion) {

          if (vsecc.getSystem() != null
              && vsecc.getSystem().contentEquals(system)
              && vsecc.getCode() != null
              && vsecc.getCode().contentEquals(code)) {

            logger.info(
                " Found Match for CodeSystem {} and Code {} in ValueSet {}", system, code, vs);
            retVal = true;
            break;
          }
        }
      }
    }

    return retVal;
  }

  /**
   * Find all document references in the bundle.
   *
   * @param res
   * @param docs
   */
  public static MessageHeader findMessageHeaderAndDocumentReferences(
      Resource res, List<DocumentReference> docs) {

    MessageHeader header = null;

    if (res.getResourceType() == ResourceType.Bundle) {

      Bundle b = (Bundle) res;

      List<BundleEntryComponent> becs = b.getEntry();

      for (BundleEntryComponent bec : becs) {

        if (bec.getResource().getResourceType() == ResourceType.MessageHeader) {

          header = (MessageHeader) bec.getResource();

        } else if (bec.getResource().getResourceType() == ResourceType.DocumentReference) {

          docs.add((DocumentReference) (bec.getResource()));

        } // if it is a doc ref
        else if (bec.getResource().getResourceType() == ResourceType.Bundle) {

          findMessageHeaderAndDocumentReferences(bec.getResource(), docs);
        }
      } // For entries
    } // if res is a bundle

    return header;
  }

  /**
   * The method takes a FHIR Resource and checks to see if the Document Bundle contains a Document
   * Reference resource When a Document Reference resource is found, it saves the attachment part of
   * the DocumentReference in an XML file. The attachment is expected to be a CDA document.
   *
   * @param res
   * @return - Pair<String, String>, the first value is the Id of the Payload, the second value is
   *     the Payload.
   */
  public static List<Pair<String, String>> saveCdaDocumentFromDocumentBundleToFile(
      String logDirectory, String actionType, Resource res) {

    List<DocumentReference> docs = new ArrayList<DocumentReference>();
    List<Pair<String, String>> outputs = new ArrayList<Pair<String, String>>();

    findMessageHeaderAndDocumentReferences(res, docs);

    for (DocumentReference docRef : docs) {

      logger.info(" Found a document reference that needs to be saved ");
      String fileName =
          logDirectory
              + actionType
              + "_"
              + docRef.getSubject().getReferenceElement().getIdPart()
              + "_"
              + docRef.getId()
              + ".xml";

      if (docRef.getContent().size() > 0) {

        DocumentReferenceContentComponent drcc = docRef.getContentFirstRep();

        if (drcc.getAttachment() != null) {
          Attachment att = drcc.getAttachment();

          String payload = new String(att.getData());

          logger.debug("Saving data to file {}", fileName);
          saveDataToFile(payload, fileName);

          Pair<String, String> p = new Pair<String, String>(docRef.getId(), payload);
          outputs.add(p);
        } // attachment not null
      } // DocRef has content
    } // For all document references.

    return outputs;
  }

  /**
   * The method saves the provided data to a file.
   *
   * @param data -- The data to be saved.
   * @param filename -- The filename to be used for saving the data.
   */
  public static void saveDataToFile(String data, String filename) {

    try (DataOutputStream outStream =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filename)))) {

      logger.info(" Writing data to file: {}", filename);
      outStream.writeBytes(data);
    } catch (IOException e) {
      logger.debug(" Unable to write data to file: {}", filename, e);
    }
  }

  /**
   * The method can save the resource to a file as a JSON object.
   *
   * @param res
   */
  public void saveResourceToFile(Resource res) {

    String fileName =
        debugDirectory + res.getResourceType().toString() + "_" + res.getId() + ".json";

    String data = jsonParser.encodeResourceToString(res);

    saveDataToFile(data, fileName);
  }

  // public static final Map<String, Bundle> eicrBundles = new HashMap<String, Bundle>();
  public void saveEicrState(String url, Resource res) {
    if (eicrBundles != null) {
      logger.info("Found actions map saving eicr bundle state....");
      if (res instanceof Bundle) {
        logger.info("Eicr bundle found...");
        Bundle eicrBundle = (Bundle) res;
        eicrBundles.put(url, eicrBundle);
      }
    } else {
      logger.info("No action map found skipping eicr bundle state save....");
    }
  }

  public void saveActionStatusState(HashMap<String, List<BsaActionStatus>> actionStatus) {

    logger.info(" ToDo : Not sure what this method is for ");

    /* if (actions != null) {
      logger.info("Found actions map saving action state....");
      for (Entry<String, List<BsaActionStatus> > entry : actionStatus.entrySet()) {

    	  if(actions.containsKey(entry.getKey())) {
    		  actions.get(entry.getKey()).addAll(entry.getValue());
    	  }
        actions.put(entry.getValue().getActionId(), entry.getValue().getActionStatus());
      }
    } else {
      logger.info("No action map found skipping action state save....");
    } */
  }

  /**
   * Checks the data provided in the bundle to examine if it is a message type bundle and if it
   * contains data related to a CDA document.
   *
   * @param res - Bundle to be examined.
   * @return
   */
  public static Boolean hasCdaData(Resource res) {

    if (res.getResourceType() == ResourceType.Bundle) {

      Bundle b = (Bundle) res;

      if (b.getType() == BundleType.MESSAGE && b.getEntry().size() > 1) {

        BundleEntryComponent bec = b.getEntryFirstRep();

        if (bec.getResource().getResourceType() == ResourceType.MessageHeader) {

          MessageHeader mh = (MessageHeader) bec.getResource();

          if (mh.getEventCoding() != null
              && mh.getEventCoding()
                  .getCode()
                  .equals(BsaTypes.getMessageTypeString(MessageType.CdaEicrMessage))) {

            return true;
          } // If Message Type
        } // MEssage Header
      } // Message Type
    } // Bundle

    return false;
  }
}
