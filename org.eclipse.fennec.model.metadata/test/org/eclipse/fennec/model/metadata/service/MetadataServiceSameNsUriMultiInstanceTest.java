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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.model.metadata.ClassMetadata;
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

    // ---- the atlas#156 signature at index level: class-URI lookup must survive -----

    @Test
    void unregisterOneVersionKeepsClassUriLookupForSurvivor() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();
        service.registerPackage(draft);
        service.registerPackage(approved);

        // Both versions carry a "Person" EClass under the same nsURI, so both resolve to the
        // same structural typeURI — the destructive-remove bug: unregistering either version
        // used to delete the shared URI entry, breaking the lookup for the survivor.
        String personURI = EcoreUtil.getURI((EClass) approved.getEClassifier("Person")).toString();
        assertNotNull(service.getClassMetadataByURI(personURI),
                "class-URI lookup must resolve while both versions are live");

        service.unregisterPackage(approved);

        ClassMetadata survivor = service.getClassMetadataByURI(personURI);
        assertNotNull(survivor,
                "class-URI lookup must survive one same-nsURI version's unregister");
        assertSame(draft.getEClassifier("Person"), survivor.getEClass(),
                "the surviving version's class must be served, not the removed one's");
    }

    @Test
    void unregisteringTheOtherVersionAlsoKeepsTheSurvivor() {
        EPackage draft = draftPackage();
        EPackage approved = approvedPackage();
        service.registerPackage(draft);
        service.registerPackage(approved);

        // Symmetric case: remove the FIRST-registered version; the newer must remain resolvable.
        String personURI = EcoreUtil.getURI((EClass) draft.getEClassifier("Person")).toString();

        service.unregisterPackage(draft);

        ClassMetadata survivor = service.getClassMetadataByURI(personURI);
        assertNotNull(survivor, "removing the older version must not drop the URI entry");
        assertSame(approved.getEClassifier("Person"), survivor.getEClass());
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

    // ---- versions getter: enumerate all versions for an nsURI (issue #16) ----------

    @Test
    void versionsIsEmptyForUnknownOrNullNsUri() {
        assertTrue(service.getPackageMetadataVersions("http://example.org/nope").isEmpty(),
                "an unknown nsURI yields no versions");
        assertTrue(service.getPackageMetadataVersions(null).isEmpty(),
                "a null nsURI yields no versions (never null)");
    }

    @Test
    void versionsReturnsTheSingleRegisteredVersion() {
        PackageMetadata draftMeta = service.registerPackage(draftPackage());

        List<PackageMetadata> versions = service.getPackageMetadataVersions(NS_URI);
        assertEquals(1, versions.size());
        assertSame(draftMeta, versions.get(0));
    }

    @Test
    void versionsReturnsAllDivergingVersionsNewestLast() {
        PackageMetadata draftMeta = service.registerPackage(draftPackage());
        PackageMetadata approvedMeta = service.registerPackage(approvedPackage());

        List<PackageMetadata> versions = service.getPackageMetadataVersions(NS_URI);
        assertEquals(2, versions.size(), "both diverging same-nsURI versions must be enumerated");
        assertSame(draftMeta, versions.get(0), "registration order: oldest first");
        assertSame(approvedMeta, versions.get(1), "registration order: newest last");
        assertSame(service.getPackageMetadata(NS_URI), versions.get(versions.size() - 1),
                "the tail must equal getPackageMetadata(String)'s best-effort newest");
    }

    @Test
    void versionsShrinksWhenAVersionIsUnregistered() {
        service.registerPackage(draftPackage());
        EPackage approved = approvedPackage();
        PackageMetadata approvedMeta = service.registerPackage(approved);

        assertEquals(2, service.getPackageMetadataVersions(NS_URI).size());

        service.unregisterPackage(approved);

        List<PackageMetadata> versions = service.getPackageMetadataVersions(NS_URI);
        assertEquals(1, versions.size(), "the unregistered version must drop out of the set");
        assertNotSame(approvedMeta, versions.get(0));
    }

    @Test
    void versionsIsEmptyAfterLastVersionUnregistered() {
        EPackage draft = draftPackage();
        service.registerPackage(draft);

        service.unregisterPackage(draft);

        assertTrue(service.getPackageMetadataVersions(NS_URI).isEmpty(),
                "once the last version is gone the nsURI has no versions");
    }

    @Test
    void versionsSnapshotIsDefensive() {
        service.registerPackage(draftPackage());

        List<PackageMetadata> snapshot = service.getPackageMetadataVersions(NS_URI);
        snapshot.clear(); // mutating the returned list must not affect the registry

        assertEquals(1, service.getPackageMetadataVersions(NS_URI).size(),
                "the getter must return a defensive copy, not the live backing list");
    }
}
