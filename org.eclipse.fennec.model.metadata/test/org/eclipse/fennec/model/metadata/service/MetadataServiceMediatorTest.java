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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.model.metadata.AttributeMetadata;
import org.eclipse.fennec.model.metadata.ClassAspect;
import org.eclipse.fennec.model.metadata.ClassMetadata;
import org.eclipse.fennec.model.metadata.FeatureAspect;
import org.eclipse.fennec.model.metadata.FeatureMetadata;
import org.eclipse.fennec.model.metadata.OperationAspect;
import org.eclipse.fennec.model.metadata.OperationMetadata;
import org.eclipse.fennec.model.metadata.PackageAspect;
import org.eclipse.fennec.model.metadata.PackageMetadata;
import org.eclipse.fennec.model.metadata.PackageProfile;
import org.eclipse.fennec.model.metadata.ReferenceMetadata;
import org.eclipse.fennec.model.metadata.api.ArtifactStore;
import org.eclipse.fennec.model.metadata.api.AspectProvider;
import org.eclipse.fennec.model.metadata.impl.PackageProfileImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for WP3 — resolve-or-build in registerPackage (the mediator wiring). Acceptance
 * criteria (positive) and non-criteria (negative).
 */
class MetadataServiceMediatorTest {

    private MetadataServiceImpl service;
    private RecordingArtifactStore store;
    private CountingProfileProvider provider;

    @BeforeEach
    void setUp() {
        service = new MetadataServiceImpl();
        store = new RecordingArtifactStore();
        provider = new CountingProfileProvider("test");
    }

    private static EPackage pkg(String nsURI) {
        EPackage p = EcoreFactory.eINSTANCE.createEPackage();
        p.setName("m");
        p.setNsPrefix("m");
        p.setNsURI(nsURI);
        EClass person = EcoreFactory.eINSTANCE.createEClass();
        person.setName("Person");
        p.getEClassifiers().add(person);
        EAttribute name = EcoreFactory.eINSTANCE.createEAttribute();
        name.setName("name");
        name.setEType(EcorePackage.Literals.ESTRING);
        person.getEStructuralFeatures().add(name);
        return p;
    }

    // ---- acceptance criteria (positive) --------------------------------------

    @Test
    void firstRegistrationBuildsAndStores() {
        service.setArtifactStore(store);
        service.registerAspectProvider(provider);

        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p);

        assertEquals(1, provider.builds.get(), "profile built once");
        assertNotNull(service.getPackageProfile(p, "test"), "profile attached to metadata");
        assertEquals(1, store.size(), "profile stored under (fingerprint, typeId)");
    }

    @Test
    void reuseOnIdenticalReRegistrationDoesNotRebuild() {
        service.setArtifactStore(store);
        service.registerAspectProvider(provider);

        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p);
        assertEquals(1, provider.builds.get());

        service.unregisterPackage(p);
        service.registerPackage(p); // same content -> same fingerprint

        assertEquals(1, provider.builds.get(), "must reuse the stored profile, not rebuild");
        assertNotNull(service.getPackageProfile(p, "test"), "reused profile is attached");
    }

    @Test
    void rebuildOnModifiedPackageWithNewFingerprint() {
        service.setArtifactStore(store);
        service.registerAspectProvider(provider);

        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p);
        assertEquals(1, provider.builds.get());

        service.unregisterPackage(p);
        // Modify content (same nsURI) -> different model fingerprint.
        EClass extra = EcoreFactory.eINSTANCE.createEClass();
        extra.setName("Company");
        p.getEClassifiers().add(extra);
        service.registerPackage(p);

        assertEquals(2, provider.builds.get(), "modified content must rebuild (no stale reuse)");
        assertEquals(2, store.size(), "old and new fingerprint artifacts both retained");
    }

    @Test
    void profilesAreIndependentPerProviderTypeId() {
        service.setArtifactStore(store);
        CountingProfileProvider a = new CountingProfileProvider("a");
        CountingProfileProvider b = new CountingProfileProvider("b");
        service.registerAspectProvider(a);
        service.registerAspectProvider(b);

        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p);

        assertEquals(1, a.builds.get());
        assertEquals(1, b.builds.get());
        assertNotNull(service.getPackageProfile(p, "a"));
        assertNotNull(service.getPackageProfile(p, "b"));
        assertEquals(2, store.size(), "one stored artifact per provider typeId");
    }

    // ---- non-criteria (negative) ---------------------------------------------

    @Test
    void noStoreConfiguredAlwaysBuilds() {
        // No store set -> backward-compatible always-build behavior.
        service.registerAspectProvider(provider);

        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p);
        service.unregisterPackage(p);
        service.registerPackage(p);

        assertEquals(2, provider.builds.get(), "without a store, every registration rebuilds");
    }

    @Test
    void unregisterDoesNotMutateStore() {
        service.setArtifactStore(store);
        service.registerAspectProvider(provider);

        EPackage p = pkg("http://test/1.0");
        PackageMetadata pm = service.registerPackage(p);
        String fp = pm.getModelFingerprint();
        assertTrue(store.resolve(fp, "test").isPresent());

        service.unregisterPackage(p);

        assertTrue(store.resolve(fp, "test").isPresent(),
                "unregister must not delete/mutate the stored artifact");
        assertEquals(1, store.size());
    }

    // ---- test doubles ---------------------------------------------------------

    /** Counts buildProfiles invocations; contributes no aspects, one profile. */
    private static final class CountingProfileProvider implements AspectProvider {
        final AtomicInteger builds = new AtomicInteger();
        private final String typeId;

        CountingProfileProvider(String typeId) {
            this.typeId = typeId;
        }

        @Override
        public String getAspectTypeId() {
            return typeId;
        }

        @Override
        public PackageAspect buildPackageAspect(PackageMetadata packageMetadata) {
            return null;
        }

        @Override
        public ClassAspect buildClassAspect(ClassMetadata classMetadata) {
            return null;
        }

        @Override
        public FeatureAspect buildFeatureAspect(FeatureMetadata featureMetadata) {
            return null;
        }

        @Override
        public FeatureAspect buildAttributeAspect(AttributeMetadata attributeMetadata) {
            return null;
        }

        @Override
        public FeatureAspect buildReferenceAspect(ReferenceMetadata referenceMetadata) {
            return null;
        }

        @Override
        public OperationAspect buildOperationAspect(OperationMetadata operationMetadata) {
            return null;
        }

        @Override
        public PackageProfile buildProfiles(PackageMetadata filteredMetadataCopy) {
            builds.incrementAndGet();
            return new TestPackageProfile();
        }
    }

    /** Concrete, instantiable PackageProfile for tests. */
    private static final class TestPackageProfile extends PackageProfileImpl {
        // default implementation
    }

    /**
     * Minimal in-memory {@link ArtifactStore} that stores/returns instances directly
     * (no EcoreUtil.copy), so the abstract-classed test profile can be held.
     */
    private static final class RecordingArtifactStore implements ArtifactStore {
        private final Map<String, EObject> map = new ConcurrentHashMap<>();

        @Override
        public Optional<EObject> resolve(String fingerprint, String typeId) {
            if (fingerprint == null || typeId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(map.get(key(fingerprint, typeId)));
        }

        @Override
        public void put(String fingerprint, String typeId, EObject artifact) {
            if (fingerprint == null || typeId == null || artifact == null) {
                return;
            }
            map.put(key(fingerprint, typeId), artifact);
        }

        int size() {
            return map.size();
        }

        private static String key(String fingerprint, String typeId) {
            return fingerprint.length() + ":" + fingerprint + typeId;
        }
    }
}
