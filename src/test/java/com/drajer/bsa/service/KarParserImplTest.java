package com.drajer.bsa.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.JsonParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import com.drajer.bsa.kar.model.KnowledgeArtifact;
import com.drajer.bsa.service.impl.KarParserImpl;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.UriType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class KarParserImplTest {
  @Test
  public void testProcessExtensions() {
    String resourceName = "Bsa/TestKnowledgeArtifact.json";

    KarParserImpl karParser = new KarParserImpl();
    ClassLoader classLoader = getClass().getClassLoader();
    File file = new File(Objects.requireNonNull(classLoader.getResource(resourceName)).getFile());
    String karPath = file.getAbsolutePath();    Bundle karBundle = null;
    IParser jsonParser = new JsonParser(FhirContext.forR4(), new LenientErrorHandler());
    try (InputStream in = new FileInputStream(karPath)) {

      karBundle = jsonParser.parseResource(Bundle.class, in);
    } catch (Exception e) {
      assert false;
    }
    KnowledgeArtifact art = new KnowledgeArtifact();
    art.setKarId(karBundle.getId());
    if (karBundle.getMeta() != null && karBundle.getMeta().getVersionId() != null)
      art.setKarVersion(karBundle.getMeta().getVersionId());
    art.setOriginalKarBundle(karBundle);
    art.setKarPath(karPath);
    List<Bundle.BundleEntryComponent> entries = karBundle.getEntry();
    PlanDefinition planDefinition = null;
    for (Bundle.BundleEntryComponent comp : entries) {
      if (Optional.ofNullable(comp).isPresent()
          && comp.getResource().getResourceType() == ResourceType.PlanDefinition) {
        planDefinition = (PlanDefinition) comp.getResource();
      } else if(Optional.ofNullable(comp).isPresent()) {
        art.addDependentResource(comp.getResource());
      }
    }
    karParser.processExtensions(Objects.requireNonNull(planDefinition), art);
    UriType result = (UriType) art.getReceiverAddresses().toArray()[0];
    assertEquals(result.getValueAsString(), "example-address");

  }
}