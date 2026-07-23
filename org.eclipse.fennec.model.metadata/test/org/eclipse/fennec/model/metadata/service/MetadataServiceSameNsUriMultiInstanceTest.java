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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.model.metadata.PackageMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for WP6 (issue #15) — multi-version same-nsURI support, ported from
 * the production finding in eclipse-fennec/model.atlas#156: two live {@link EPackage}
 * services with the same nsURI but diverging content (stage-aware registration, e.g.
 * draft vs. approved branch) must coexist, and one version's unregister must never
 * remove the metadata another live version needs.
 * <p>
 * Deliberately mechanism-agnostic (asserts observable service behavior only) so it
 * serves as an acceptance test for whichever keying design lands.
 * </p>
 */
class MetadataServiceSameNsUriMultiInstanceTest {

    private static final String NS_URI = "http://example.org/person/1.0";

    private MetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MetadataServiceImpl();
    }

    /** The draft variant: Person{name}. */
    private static EPackage draftPackage() {
        return personPackage("name");
    }

    /** The approved variant: Person{fullName} — same nsURI, diverging content. */
    private static EPackage approvedPackage() {
        return personPackage("fullName");
    }

    private static EPackage personPackage(String attributeName) {
        EPackage p = EcoreFactory.eINSTANCE.createEPackage();
        p.setName("person");
        p.setNsPrefix("person");
        p.setNsURI(NS_URI);
        EClass person = EcoreFactory.eINSTANCE.createEClass();
        person.setName("Person");
        p.getEClassifiers().add(person);
        EAttribute attribute = EcoreFactory.eINSTANCE.createEAttribute();
        attribute.setName(attributeName);
        attribute.setEType(EcorePackage.Literals.ESTRING);
        person.getEStructuralFeatures().add(attribute);
        return p;
    }

    // ---- the atlas#156 repro: unregister one stage, the survivor must be served ----

    @Test
    void unregisterOneVersionKeepsServingTheSurvivor() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();

        service.registerPackage(draft);
        String approvedFp = service.registerPackage(approved).getModelFingerprint();

        service.unregisterPackage(approved);

        PackageMetadata survivor = service.getPackageMetadata(NS_URI);
        assertNotNull(survivor, "draft must still be served after approved's unregister");
        assertSame(draft, survivor.getEPackage());
        assertNotNull(service.getPackageMetadata(draft));
        assertNull(service.getPackageMetadataByFingerprint(approvedFp),
                "the unregistered version must be gone — not the survivor");
    }

    // ---- coexistence: two diverging versions, each with its own metadata ----------

    @Test
    void divergingVersionsOfSameNsUriCoexist() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();

        PackageMetadata draftMeta = service.registerPackage(draft);
        PackageMetadata approvedMeta = service.registerPackage(approved);

        assertNotSame(draftMeta, approvedMeta,
                "diverging content must never silently reuse the other version's metadata");
        assertNotEquals(draftMeta.getModelFingerprint(), approvedMeta.getModelFingerprint());
        assertSame(draft, draftMeta.getEPackage());
        assertSame(approved, approvedMeta.getEPackage());
    }

    @Test
    void eachVersionResolvesItsOwnMetadataByInstance() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();
        service.registerPackage(draft);
        service.registerPackage(approved);

        assertEquals("name", service.getPackageMetadata(draft)
                .getClasses().get(0).getFeatures().get(0).getName());
        assertEquals("fullName", service.getPackageMetadata(approved)
                .getClasses().get(0).getFeatures().get(0).getName());
    }

    // ---- dedupe: identical content on two instances is one model version ----------

    @Test
    void identicalContentDedupesOntoOneEntry() {
        EPackage branchA = draftPackage();
        EPackage branchB = draftPackage(); // different instance, identical content

        PackageMetadata a = service.registerPackage(branchA);
        PackageMetadata b = service.registerPackage(branchB);

        assertSame(a, b, "identical content must dedupe onto one model-version entry");
    }

    @Test
    void dedupedEntrySurvivesUntilLastRegistrationIsGone() {
        EPackage branchA = draftPackage();
        EPackage branchB = draftPackage();
        service.registerPackage(branchA);
        service.registerPackage(branchB);

        service.unregisterPackage(branchA);
        assertNotNull(service.getPackageMetadata(NS_URI),
                "one of two live registrations unbinding must not drop the entry");

        service.unregisterPackage(branchB);
        assertNull(service.getPackageMetadata(NS_URI),
                "the last registration unbinding removes the entry");
    }

    // ---- fingerprint lookup -------------------------------------------------------

    @Test
    void fingerprintLookupResolvesTheExactVersion() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();
        PackageMetadata draftMeta = service.registerPackage(draft);
        PackageMetadata approvedMeta = service.registerPackage(approved);

        assertSame(draftMeta,
                service.getPackageMetadataByFingerprint(draftMeta.getModelFingerprint()));
        assertSame(approvedMeta,
                service.getPackageMetadataByFingerprint(approvedMeta.getModelFingerprint()));
        assertNull(service.getPackageMetadataByFingerprint("fp1:unknown"));
    }

    // ---- stateless pull: no prior registration required ---------------------------

    @Test
    void pullResolvesWithoutPriorRegistration() {
        EPackage draft = draftPackage();

        PackageMetadata pulled = service.getPackageMetadata(draft);

        assertNotNull(pulled, "the pull path must build on miss (resolve-or-build)");
        assertSame(pulled, service.getPackageMetadata(draft), "second pull is a cache hit");
        assertSame(pulled, service.getPackageMetadataByFingerprint(pulled.getModelFingerprint()));
    }

    @Test
    void unregisterNeverEvictsPullCreatedEntries() {
        EPackage draft = draftPackage();
        PackageMetadata pulled = service.getPackageMetadata(draft);

        service.unregisterPackage(draftPackage()); // equal content, was never registered

        assertSame(pulled, service.getPackageMetadata(draft),
                "a pull-created cache entry carries no liveness and must not be evicted");
    }

    @Test
    void pullAfterRegistrationReturnsTheRegisteredEntry() {
        EPackage draft = draftPackage();
        PackageMetadata registered = service.registerPackage(draft);

        assertSame(registered, service.getPackageMetadata(draftPackage()),
                "an equal-content instance must resolve to the same model-version entry");
    }
}
