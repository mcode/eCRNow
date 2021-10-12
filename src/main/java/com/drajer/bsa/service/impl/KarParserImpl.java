package com.drajer.bsa.service.impl;

import ca.uhn.fhir.parser.IParser;
import com.drajer.bsa.ehr.subscriptions.SubscriptionGenerator;
import com.drajer.bsa.kar.action.EvaluateMeasure;
import com.drajer.bsa.kar.action.SubmitReport;
import com.drajer.bsa.kar.action.ValidateReport;
import com.drajer.bsa.kar.condition.BsaCqlCondition;
import com.drajer.bsa.kar.condition.BsaFhirPathCondition;
import com.drajer.bsa.kar.model.BsaAction;
import com.drajer.bsa.kar.model.BsaCondition;
import com.drajer.bsa.kar.model.BsaRelatedAction;
import com.drajer.bsa.kar.model.KnowledgeArtifact;
import com.drajer.bsa.kar.model.KnowledgeArtifactRepositorySystem;
import com.drajer.bsa.model.BsaTypes;
import com.drajer.bsa.model.BsaTypes.ActionType;
import com.drajer.bsa.scheduler.BsaScheduler;
import com.drajer.bsa.service.KarParser;
import com.drajer.bsa.utils.BsaServiceUtils;
import com.drajer.bsa.utils.SubscriptionUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionConditionComponent;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionRelatedActionComponent;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.hl7.fhir.r4.model.TriggerDefinition.TriggerType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;
import org.hl7.fhir.r4.model.ValueSet;
import org.opencds.cqf.cql.evaluator.measure.r4.R4MeasureProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

/**
 *
 *
 * <h1>KarParserImpl</h1>
 *
 * This is an implementation class for the KarParser Interface.
 *
 * @author nbashyam
 */
@Service
@Transactional
public class KarParserImpl implements KarParser {

  private final Logger logger = LoggerFactory.getLogger(KarParserImpl.class);
  private static final Logger logger2 = LoggerFactory.getLogger(KarParserImpl.class);

  @Value("${kar.directory}")
  String karDirectory;

  @Value("${ignore.timers}")
  Boolean ignoreTimers;

  @Value("${measure-reporting-period.start}")
  String measurePeriodStart;

  @Value("${measure-reporting-period.end}")
  String measurePeriodEnd;

  @Autowired SubscriptionGenerator subscriptionGenerator;

  @Autowired BsaServiceUtils utils;

  // Autowired to pass to action processors.
  @Autowired BsaScheduler scheduler;

  // Autowired to pass to action processors.
  @Autowired R4MeasureProcessor measureProcessor;

  // Autowired to pass to actions
  @Autowired
  @Qualifier("jsonParser")
  IParser jsonParser;

  @Autowired RestTemplate restTemplate;

  @Value("${report-validator.endpoint}")
  private String validatorEndpoint;

  @Value("${report-submission.endpoint}")
  private String reportSubmissionEndpoint;

  private static String[] KAR_FILE_EXT = {"json"};
  private static String JSON_KAR_EXT = "json";
  private static String RECEIVER_ADDRESS_URL =
      "http://hl7.org/fhir/us/medmorph/StructureDefinition/ext-receiverAddress";
  private static HashMap<String, String> actionClasses = new HashMap<>();

  // Load the Topic to Named Event Map.
  static {
    try (InputStream input =
        SubscriptionUtils.class.getClassLoader().getResourceAsStream("action-classes.properties")) {

      Properties prop = new Properties();
      prop.load(input);

      prop.forEach((key, value) -> actionClasses.put((String) key, (String) value));

    } catch (IOException ex) {
      logger2.error("Error while loading Action Classes from Properties File ");
    }
  }

  public BsaAction getAction(String actionId) {

    BsaAction instance = null;
    if (actionClasses != null && actionClasses.containsKey(actionId)) {
      try {
        instance = (BsaAction) (Class.forName(actionClasses.get(actionId)).newInstance());
      } catch (InstantiationException e) {
        logger.error(" Error instantiating the object {}", e);
      } catch (IllegalAccessException e) {
        logger.error(" Error instantiating the object {}", e);
      } catch (ClassNotFoundException e) {
        logger.error(" Error instantiating the object {}", e);
      }
    }

    return instance;
  }

  @PostConstruct
  public void initializeRepository() {
    loadKars();
  }

  @Override
  public void loadKars() {
    loadKarsFromDirectory(karDirectory);
  }

  public void loadKarsFromDirectory(String dirName) {

    // Load each of the Knowledge Artifact Bundles.
    File folder = new File(dirName);
    List<File> kars = (List<File>) FileUtils.listFiles(folder, null, false);

    for (File kar : kars) {

      if (kar.isFile() && JSON_KAR_EXT.contentEquals(FilenameUtils.getExtension(kar.getName()))) {

        processKar(kar);

      } // For a File
      else if (kar.isDirectory()) {

        logger.info(" About to process directory : {}", kar.getName());
        loadKarsFromDirectory(kar.getName());
      } else {

        logger.info(" Ignoring File {} as it is not the right extension", kar.getName());
      }
    } // For
  }

  private void processKar(File kar) {
    logger.info(" Processing File : {}", kar);

    Bundle karBundle = utils.readKarFromFile(kar.getPath());

    if (karBundle != null && (karBundle.getType() == Bundle.BundleType.COLLECTION)) {

      logger.info(" Successfully read the KAR from File ");

      KnowledgeArtifact art = new KnowledgeArtifact();

      // Setup the Id.
      art.setKarId(karBundle.getId());

      // Setup Version.
      if (karBundle.getMeta() != null && karBundle.getMeta().getVersionId() != null)
        art.setKarVersion(karBundle.getMeta().getVersionId());

      // Set Bundle
      art.setOriginalKarBundle(karBundle);
      art.setKarPath(kar.getPath());

      List<BundleEntryComponent> entries = karBundle.getEntry();

      for (BundleEntryComponent comp : entries) {

        if (Optional.ofNullable(comp).isPresent()
            && comp.getResource().getResourceType() == ResourceType.ValueSet) {
          logger.debug(" Processing ValueSet ");
          processValueSet((ValueSet) comp.getResource(), art);
        } else if (Optional.ofNullable(comp).isPresent()
            && comp.getResource().getResourceType() == ResourceType.PlanDefinition) {
          logger.info(" Processing PlanDefinition ");
          processPlanDefinition((PlanDefinition) comp.getResource(), art);
          art.initializeRelatedActions();
        } else if (Optional.ofNullable(comp).isPresent()
            && comp.getResource().getResourceType() == ResourceType.Library) {
          logger.info(" Processing Library");
        }
      }

      KnowledgeArtifactRepositorySystem.getInstance().add(art);
      art.printKarSummary();

    } else {

      logger.error(
          " Bundle for Path : {} cannot be processed because it is either non existent or of the wrong bundle type.",
          kar);
    }
  }

  private void processPlanDefinition(PlanDefinition plan, KnowledgeArtifact art) {

    processExtensions(plan, art);
    subscriptionGenerator.subscriptionsFromPlanDef(plan);
    List<PlanDefinitionActionComponent> actions = plan.getAction();

    for (PlanDefinitionActionComponent act : actions) {

      if (act.getCodeFirstRep() != null && act.getCodeFirstRep().getCodingFirstRep() != null) {

        Coding cd = act.getCodeFirstRep().getCodingFirstRep();

        BsaAction action = getAction(cd.getCode());
        action.setActionId(act.getId(), plan.getUrl());
        action.setScheduler(scheduler);
        action.setJsonParser(jsonParser);
        action.setRestTemplate(restTemplate);
        action.setIgnoreTimers(ignoreTimers);
        action.setType(BsaTypes.getActionType(cd.getCode()));

        populateAction(plan, act, action);

        // Setup the artifact details.
        art.addAction(action);
        art.addFirstLevelAction(action);
        art.addTriggerEvent(action);
      }
    }
  }

  public void processExtensions(PlanDefinition plan, KnowledgeArtifact art) {

    if (plan.hasExtension()) {

      Extension ext = plan.getExtensionByUrl(RECEIVER_ADDRESS_URL);

      if (ext != null) {

        Type t = ext.getValue();
        if (t instanceof PrimitiveType) {
          PrimitiveType<?> i = (PrimitiveType<?>) t;
          if (i instanceof UriType) {

            logger.info(" Found Receiver Address {}", i.getValueAsString());
            art.addReceiverAddress((UriType) i);
          }
        }
      }
    }
  }

  private void populateOutputDataReq(PlanDefinitionActionComponent ac, BsaAction action) {

    List<DataRequirement> drs = ac.getOutput();
    action.setOutputData(drs);
  }

  private void populateInputDataReq(PlanDefinitionActionComponent ac, BsaAction action) {

    List<DataRequirement> drs = ac.getInput();
    action.setInputData(drs);

    for (DataRequirement dr : drs) {
      try {
        ResourceType rt = ResourceType.fromCode(dr.getType());
        action.addInputResourceType(dr.getId(), rt);
      } catch (FHIRException ex) {
        logger.error(" Type specified is not a resource Type {}", dr.getType());
      }
    }
  }

  private void populateAction(
      PlanDefinition plan, PlanDefinitionActionComponent act, BsaAction action) {

    if (act.hasTrigger()) {
      action.setNamedEventTriggers(getNamedEvents(act));
    }

    if (act.hasInput()) {
      populateInputDataReq(act, action);
    }

    if (act.hasOutput()) {
      populateOutputDataReq(act, action);
    }

    if (act.hasCondition()) {
      populateCondition(act, action);
    }

    if (act.hasRelatedAction()) {
      populateRelatedAction(plan, act, action);
    }

    if (act.hasAction()) {
      populateSubActions(plan, act, action);
    }

    if (act.hasDefinitionUriType()) {
      action.setMeasureUri(act.getDefinitionUriType().getValue());
    }

    if (act.hasTiming()) {

      // Todo - handle timing elements in the action itslef.
    }

    // TODO: Why are these populated at this point?
    action.setJsonParser(this.jsonParser);
    action.setIgnoreTimers(this.ignoreTimers);

    if (action.getType() == ActionType.EvaluateMeasure) {
      setMeasureParameters(act, action);
    } else if (action.getType() == ActionType.ValidateReport) {
      ValidateReport vr = (ValidateReport) (action);
      vr.setValidatorEndpoint(validatorEndpoint);
    } else if (action.getType() == ActionType.SubmitReport) {
      SubmitReport sr = (SubmitReport) (action);
      sr.setSubmissionEndpoint(reportSubmissionEndpoint);
    }
  }

  private void setMeasureParameters(PlanDefinitionActionComponent act, BsaAction action) {

    // Setup the MeasureReportId that is generated.
    if (act.hasOutput()) {

      for (DataRequirement dr : act.getOutput()) {

        if (dr.getType() != null
            && dr.getType().contentEquals(ResourceType.MeasureReport.toString())) {
          EvaluateMeasure em = (EvaluateMeasure) (action);
          em.setMeasureReportId(dr.getId());

          if (measurePeriodStart != null) {
            em.setPeriodStart(measurePeriodStart);
          }

          if (measurePeriodEnd != null) {
            em.setPeriodEnd(measurePeriodEnd);
          }

          // setup the Measure Processor
          em.setMeasureProcessor(measureProcessor);
        }
      }
    }
  }

  private void populateSubActions(
      PlanDefinition plan, PlanDefinitionActionComponent ac, BsaAction action) {

    List<PlanDefinitionActionComponent> actions = ac.getAction();

    if (actions != null && actions.size() > 0) {
      for (PlanDefinitionActionComponent act : actions) {

        if (act.getCodeFirstRep() != null && act.getCodeFirstRep().getCodingFirstRep() != null) {

          Coding cd = act.getCodeFirstRep().getCodingFirstRep();

          BsaAction subAction = getAction(cd.getCode());
          subAction.setActionId(act.getId(), plan.getUrl());
          subAction.setScheduler(scheduler);
          action.setIgnoreTimers(ignoreTimers);
          subAction.setType(BsaTypes.getActionType(cd.getCode()));

          populateAction(plan, act, subAction);

          // Setup the artifact details.
          action.addAction(subAction);
        }
      }
    }
  }

  private void populateCondition(PlanDefinitionActionComponent ac, BsaAction action) {

    List<PlanDefinitionActionConditionComponent> conds = ac.getCondition();

    for (PlanDefinitionActionConditionComponent con : conds) {

      if (con.getExpression() != null
          && (Expression.ExpressionLanguage.fromCode(con.getExpression().getLanguage())
              .equals(Expression.ExpressionLanguage.TEXT_FHIRPATH))) {

        logger.info(" Found a FHIR Path Expression ");
        BsaCondition bc = new BsaFhirPathCondition();
        bc.setLogicExpression(con.getExpression());
        action.addCondition(bc);
      } else if (con.getExpression() != null
          && (Expression.ExpressionLanguage.fromCode(con.getExpression().getLanguage())
              .equals(Expression.ExpressionLanguage.TEXT_CQL))) {

        logger.info(" Found a CQL Expression ");
        BsaCondition bc = new BsaCqlCondition();
        bc.setLogicExpression(con.getExpression());
        action.addCondition(bc);
      } else {
        logger.error(" Unknown type of Expression passed, cannot process ");
      }
    }
  }

  private void populateRelatedAction(
      PlanDefinition plan, PlanDefinitionActionComponent ac, BsaAction action) {

    List<PlanDefinitionActionRelatedActionComponent> racts = ac.getRelatedAction();

    for (PlanDefinitionActionRelatedActionComponent ract : racts) {

      if (ract.getRelationship() == ActionRelationshipType.BEFORESTART) {

        BsaRelatedAction bract = new BsaRelatedAction();
        bract.setRelationship(ract.getRelationship());
        bract.setRelatedActionId(ract.getActionId(), plan.getUrl());

        if (ract.getOffsetDuration() != null) {
          bract.setDuration(ract.getOffsetDuration());
        }
        action.addRelatedAction(bract);
      }
    }
  }

  private Set<String> getNamedEvents(PlanDefinitionActionComponent ac) {

    Set<String> events = new HashSet<String>();

    List<TriggerDefinition> triggers = ac.getTrigger();

    for (TriggerDefinition td : triggers) {
      if (td.getType() == TriggerType.NAMEDEVENT) events.add(td.getId());
    }

    return events;
  }

  private void processValueSet(ValueSet vs, KnowledgeArtifact art) {

    art.addDependentValueSet(vs);
  }
}
