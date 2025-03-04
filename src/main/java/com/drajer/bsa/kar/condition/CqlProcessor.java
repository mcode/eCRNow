package com.drajer.bsa.kar.condition;

import com.drajer.bsa.kar.model.BsaAction;
import com.drajer.bsa.kar.model.BsaCondition;
import com.drajer.bsa.model.KarProcessingData;
import java.util.HashSet;
import java.util.Set;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Parameters;
import org.opencds.cqf.cql.evaluator.library.LibraryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlProcessor implements BsaConditionProcessor {
  private static final Logger logger = LoggerFactory.getLogger(CqlProcessor.class);

  private LibraryProcessor libraryProcessor;

  @Override
  public Boolean evaluateExpression(BsaCondition cond, BsaAction act, KarProcessingData kd) {
    Set<String> expressions = new HashSet<>();
    expressions.add(cond.getLogicExpression().getExpression());
    if (!(cond instanceof BsaCqlCondition)) {
      logger.error("Expected a BsaCqlCondition, but found: " + cond);
    }
    BsaCqlCondition cqlCondition = (BsaCqlCondition) cond;
    Parameters result =
        (Parameters)
            this.libraryProcessor.evaluate(
                cqlCondition.getUrl(),
                cqlCondition.getPatientId(),
                null,
                cqlCondition.getLibraryEndpoint(),
                cqlCondition.getTerminologyEndpoint(),
                cqlCondition.getDataEndpoint(),
                kd.getInputResourcesAsBundle(),
                expressions);

    BooleanType value =
        (BooleanType) result.getParameter(cond.getLogicExpression().getExpression());

    return value.getValue();
  }

  public LibraryProcessor getLibraryProcessor() {
    return libraryProcessor;
  }

  public void setLibraryProcessor(LibraryProcessor libraryProcessor) {
    this.libraryProcessor = libraryProcessor;
  }
}
