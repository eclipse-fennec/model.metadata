/********************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 ********************************************************************/
package org.eclipse.fennec.model.metadata.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.model.metadata.api.FingerprintService;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefaultFingerprintService} — acceptance criteria (positive) and
 * non-criteria (negative) from the WP1 work package.
 */
class DefaultFingerprintServiceTest {

    private static final String GENMODEL = "http://www.eclipse.org/emf/2002/GenModel";

    private final FingerprintService service = new DefaultFingerprintService();

    // ---- test model builders -------------------------------------------------

    /** A standard package: Person(id[id], name) -> Address(street); Person.greet(greeting):String. */
    private static EPackage base(String nsURI) {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("m");
        pkg.setNsPrefix("m");
        pkg.setNsURI(nsURI);

        EClass person = EcoreFactory.eINSTANCE.createEClass();
        person.setName("Person");
        pkg.getEClassifiers().add(person);

        EAttribute id = EcoreFactory.eINSTANCE.createEAttribute();
        id.setName("id");
        id.setEType(EcorePackage.Literals.ESTRING);
        id.setID(true);
        person.getEStructuralFeatures().add(id);

        EAttribute name = EcoreFactory.eINSTANCE.createEAttribute();
        name.setName("name");
        name.setEType(EcorePackage.Literals.ESTRING);
        person.getEStructuralFeatures().add(name);

        EClass address = EcoreFactory.eINSTANCE.createEClass();
        address.setName("Address");
        pkg.getEClassifiers().add(address);

        EAttribute street = EcoreFactory.eINSTANCE.createEAttribute();
        street.setName("street");
        street.setEType(EcorePackage.Literals.ESTRING);
        address.getEStructuralFeatures().add(street);

        EReference addr = EcoreFactory.eINSTANCE.createEReference();
        addr.setName("address");
        addr.setEType(address);
        addr.setContainment(true);
        person.getEStructuralFeatures().add(addr);

        EOperation greet = EcoreFactory.eINSTANCE.createEOperation();
        greet.setName("greet");
        greet.setEType(EcorePackage.Literals.ESTRING);
        EParameter greeting = EcoreFactory.eINSTANCE.createEParameter();
        greeting.setName("greeting");
        greeting.setEType(EcorePackage.Literals.ESTRING);
        greet.getEParameters().add(greeting);
        person.getEOperations().add(greet);

        return pkg;
    }

    private static EClass cls(EPackage pkg, String name) {
        return (EClass) pkg.getEClassifier(name);
    }

    // ---- acceptance criteria (positive) --------------------------------------

    @Test
    void reproducible_sameContentSameFingerprint() {
        String a = service.fingerprint(base("http://test/1.0"));
        String b = service.fingerprint(base("http://test/1.0"));
        assertEquals(a, b, "same content must yield the same fingerprint");
        assertTrue(a.startsWith("fp1:"), "fingerprint should carry the scheme tag");
    }

    @Test
    void reproducible_acrossServiceInstances() {
        String a = new DefaultFingerprintService().fingerprint(base("http://test/1.0"));
        String b = new DefaultFingerprintService().fingerprint(base("http://test/1.0"));
        assertEquals(a, b, "fingerprint must not depend on the service instance");
    }

    @Test
    void reproducible_repeatedCallSamePackage() {
        EPackage pkg = base("http://test/1.0");
        assertEquals(service.fingerprint(pkg), service.fingerprint(pkg));
    }

    @Test
    void nsUriIsPartOfTheKey() {
        assertNotEquals(service.fingerprint(base("http://a/1.0")), service.fingerprint(base("http://b/1.0")),
                "different nsURI must yield different fingerprints");
    }

    @Test
    void sameNsUriDifferentContentDiffers() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        EClass extra = EcoreFactory.eINSTANCE.createEClass();
        extra.setName("Company");
        b.getEClassifiers().add(extra);
        assertNotEquals(service.fingerprint(a), service.fingerprint(b),
                "same nsURI is not the key — content change must change the fingerprint");
    }

    @Test
    void addedFeatureChangesFingerprint() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        EAttribute age = EcoreFactory.eINSTANCE.createEAttribute();
        age.setName("age");
        age.setEType(EcorePackage.Literals.EINT);
        cls(b, "Person").getEStructuralFeatures().add(age);
        assertNotEquals(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void renamedFeatureChangesFingerprint() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        cls(b, "Person").getEStructuralFeatures().get(1).setName("fullName");
        assertNotEquals(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void changedFeatureTypeChangesFingerprint() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        cls(b, "Person").getEStructuralFeatures().get(1).setEType(EcorePackage.Literals.EINT);
        assertNotEquals(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void addedOperationAndParameterChangeFingerprint() {
        EPackage a = base("http://test/1.0");
        EPackage withOp = base("http://test/1.0");
        EOperation op = EcoreFactory.eINSTANCE.createEOperation();
        op.setName("rename");
        cls(withOp, "Person").getEOperations().add(op);
        assertNotEquals(service.fingerprint(a), service.fingerprint(withOp), "new operation must change fingerprint");

        EPackage withParam = base("http://test/1.0");
        EParameter p = EcoreFactory.eINSTANCE.createEParameter();
        p.setName("locale");
        p.setEType(EcorePackage.Literals.ESTRING);
        cls(withParam, "Person").getEOperations().get(0).getEParameters().add(p);
        assertNotEquals(service.fingerprint(a), service.fingerprint(withParam), "new parameter must change fingerprint");
    }

    @Test
    void classifierOrderIsIrrelevant() {
        EPackage a = base("http://test/1.0");
        // b: same classes, added in reverse order
        EPackage b = EcoreFactory.eINSTANCE.createEPackage();
        b.setName("m");
        b.setNsPrefix("m");
        b.setNsURI("http://test/1.0");
        // move Address before Person by rebuilding in reverse
        EPackage tmp = base("http://test/1.0");
        EClass person = cls(tmp, "Person");
        EClass address = cls(tmp, "Address");
        b.getEClassifiers().add(address);
        b.getEClassifiers().add(person);
        assertEquals(service.fingerprint(a), service.fingerprint(b),
                "classifier order in the package must not affect the fingerprint");
    }

    @Test
    void derivationInputsAreFolded() {
        EPackage pkg = base("http://test/1.0");
        String none = service.fingerprint(pkg);
        String withInput = service.fingerprint(pkg, "oclEngine=1.2.0");
        assertNotEquals(none, withInput, "a derivation input must change the fingerprint");

        String orderA = service.fingerprint(pkg, "oclEngine=1.2.0", "eorm=abc");
        String orderB = service.fingerprint(pkg, "eorm=abc", "oclEngine=1.2.0");
        assertEquals(orderA, orderB, "derivation input order must not matter");

        String otherValue = service.fingerprint(pkg, "oclEngine=1.3.0");
        assertNotEquals(withInput, otherValue, "a changed input value must change the fingerprint");
    }

    // ---- non-criteria (negative) ---------------------------------------------

    @Test
    void documentationOnlyMetadataIsIgnored() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        EAnnotation doc = EcoreFactory.eINSTANCE.createEAnnotation();
        doc.setSource(GENMODEL);
        doc.getDetails().put("documentation", "A person in the system.");
        cls(b, "Person").getEAnnotations().add(doc);
        assertEquals(service.fingerprint(a), service.fingerprint(b),
                "adding a documentation-only annotation must NOT change the fingerprint");
    }

    @Test
    void changingOnlyDocumentationIsIgnored() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        EAnnotation docA = EcoreFactory.eINSTANCE.createEAnnotation();
        docA.setSource(GENMODEL);
        docA.getDetails().put("documentation", "first text");
        cls(a, "Person").getEAnnotations().add(docA);
        EAnnotation docB = EcoreFactory.eINSTANCE.createEAnnotation();
        docB.setSource(GENMODEL);
        docB.getDetails().put("documentation", "completely different text");
        cls(b, "Person").getEAnnotations().add(docB);
        assertEquals(service.fingerprint(a), service.fingerprint(b),
                "changing documentation text must NOT change the fingerprint");
    }

    @Test
    void nonDocumentationAnnotationChangesFingerprint() {
        EPackage a = base("http://test/1.0");
        EPackage b = base("http://test/1.0");
        EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
        ann.setSource("http://example.org/custom");
        ann.getDetails().put("unit", "celsius");
        cls(b, "Person").getEAnnotations().add(ann);
        assertNotEquals(service.fingerprint(a), service.fingerprint(b),
                "a log-relevant (non-documentation) annotation must change the fingerprint");
    }

    @Test
    void nullPackageReturnsNullNotException() {
        assertNull(service.fingerprint(null));
        assertNull(service.fingerprint(null, "x=1"));
    }

    @Test
    void nullInputsAreTolerated() {
        EPackage pkg = base("http://test/1.0");
        String none = service.fingerprint(pkg);
        assertEquals(none, service.fingerprint(pkg, (String[]) null),
                "null inputs array must behave like no inputs");
        // a null token among inputs must be ignored, not throw
        assertEquals(service.fingerprint(pkg, "a=1"), service.fingerprint(pkg, "a=1", null));
    }
}
