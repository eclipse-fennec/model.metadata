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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
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
import org.eclipse.fennec.model.metadata.api.AspectProvider;
import org.eclipse.fennec.model.metadata.impl.PackageAspectImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for WP5 — service properties as transient build context and the cached
 * {@code modelFingerprint} on {@link PackageMetadata}. Acceptance criteria (positive)
 * and non-criteria (negative).
 */
class MetadataServicePropertiesTest {

    private MetadataServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MetadataServiceImpl();
    }

    /** A minimal package with one class (Person{name}). */
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
    void servicePropertiesAreCapturedOnPackageMetadata() {
        PackageMetadata pm = service.registerPackage(pkg("http://test/1.0"),
                Map.of("persistence.target", "jpa", "sensinact.enabled", "true"));
        assertEquals("jpa", pm.getProperties().get("persistence.target"));
        assertEquals("true", pm.getProperties().get("sensinact.enabled"));
    }

    @Test
    void modelFingerprintIsComputedAndCached() {
        EPackage p = pkg("http://test/1.0");
        PackageMetadata pm = service.registerPackage(p, null);
        String expected = new DefaultFingerprintService().fingerprint(p);
        assertEquals(expected, pm.getModelFingerprint());
        assertTrue(pm.getModelFingerprint().startsWith("fp1:"));
    }

    @Test
    void providerCanDeclineBuildBasedOnProperty() {
        service.registerAspectProvider(new SkippableProvider());

        EPackage skip = pkg("http://skip/1.0");
        service.registerPackage(skip, Map.of("build.skip", "true"));
        assertNull(service.getPackageAspect(skip, "test"), "provider should decline when build.skip=true");

        EPackage build = pkg("http://build/1.0");
        service.registerPackage(build, Map.of());
        assertNotNull(service.getPackageAspect(build, "test"), "provider should build when no skip flag");
    }

    @Test
    void registrationWorksWithoutPropertiesOrExternalFingerprint() {
        PackageMetadata a = service.registerPackage(pkg("http://a/1.0"), null);
        PackageMetadata b = service.registerPackage(pkg("http://b/1.0"), Map.of());
        assertNotNull(a.getModelFingerprint());
        assertNotNull(b.getModelFingerprint());
        assertTrue(a.getProperties().isEmpty());
        assertTrue(b.getProperties().isEmpty());
    }

    @Test
    void propertiesRefreshedOnReRegistration() {
        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p, Map.of("v", "1"));
        service.unregisterPackage(p);
        PackageMetadata pm = service.registerPackage(p, Map.of("v", "2"));
        assertEquals("2", pm.getProperties().get("v"));
    }

    // ---- non-criteria (negative) ---------------------------------------------

    @Test
    void propertiesFeatureIsTransientButFingerprintIsNot() {
        PackageMetadata pm = service.registerPackage(pkg("http://test/1.0"), Map.of("k", "v"));
        EStructuralFeature properties = pm.eClass().getEStructuralFeature("properties");
        EStructuralFeature modelFingerprint = pm.eClass().getEStructuralFeature("modelFingerprint");
        assertTrue(properties.isTransient(), "properties must be transient (not serialized/replicated)");
        assertFalse(modelFingerprint.isTransient(), "modelFingerprint is a cached value and is serialized");
    }

    @Test
    void nonStringPropertyValuesAreStringifiedNotCrashing() {
        PackageMetadata pm = service.registerPackage(pkg("http://test/1.0"),
                Map.<String, Object>of("multi", new String[] { "a", "b" }, "num", 42));
        assertEquals("[a, b]", pm.getProperties().get("multi"));
        assertEquals("42", pm.getProperties().get("num"));
    }

    @Test
    void propertiesGoneAfterUnregister() {
        EPackage p = pkg("http://test/1.0");
        service.registerPackage(p, Map.of("v", "1"));
        service.unregisterPackage(p);
        assertNull(service.getPackageMetadata("http://test/1.0"));

        PackageMetadata fresh = service.registerPackage(p, null);
        assertTrue(fresh.getProperties().isEmpty(), "a fresh registration must not carry old properties");
    }

    // ---- test double ---------------------------------------------------------

    /** Reads the package properties and declines (returns no aspect) when build.skip=true. */
    private static final class SkippableProvider implements AspectProvider {
        @Override
        public String getAspectTypeId() {
            return "test";
        }

        @Override
        public PackageAspect buildPackageAspect(PackageMetadata packageMetadata) {
            if ("true".equals(packageMetadata.getProperties().get("build.skip"))) {
                return null;
            }
            return new TestPackageAspect();
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
            return null;
        }
    }

    private static final class TestPackageAspect extends PackageAspectImpl {
        // default implementation
    }
}
