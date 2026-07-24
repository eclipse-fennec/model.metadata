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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.fennec.model.metadata.ClassMetadata;
import org.eclipse.fennec.model.metadata.FeatureMetadata;
import org.eclipse.fennec.model.metadata.OperationMetadata;
import org.eclipse.fennec.model.metadata.PackageMetadata;
import org.eclipse.fennec.model.metadata.api.MetadataIndex;

/**
 * In-memory Map-based implementation of {@link MetadataIndex}.
 * <p>
 * Uses ConcurrentHashMaps for thread-safe indexed lookups. Maintains multiple
 * indexes for different query patterns:
 * <ul>
 *   <li>By URI (for URI TypeStrategy)</li>
 *   <li>By class name + nsURI (for NAME TypeStrategy)</li>
 *   <li>By instanceClassName + nsURI (for CLASS TypeStrategy)</li>
 *   <li>By instanceClassName globally (for CLASS TypeStrategy without context)</li>
 *   <li>By class name globally</li>
 * </ul>
 * </p>
 *
 * @author Mark Hoffmann
 * @since 2026-01-26
 */
public class MapBasedMetadataIndex implements MetadataIndex {

    // All indexes are multi-valued (key -> versions). Under same-nsURI multi-version
    // registration two diverging versions of a package produce colliding keys: identical
    // typeURI ("nsURI#//Class"), identical "nsURI::name"/"nsURI::instanceClassName"
    // composites, and identical feature/operation URIs for features present in both. A
    // single-valued map would (a) last-wins overwrite the entry on register and (b) delete
    // the shared entry entirely when EITHER version unregisters, breaking lookups for the
    // surviving version. Storing a per-key list keeps each version's entry independent:
    // register appends, unregister removes only that instance, and single-result lookups
    // return the most recently indexed version (best effort, mirroring the service's
    // newest-version resolution for name/URI lookups).

    // Primary indexes
    private final Map<String, List<ClassMetadata>> classesByURI = new ConcurrentHashMap<>();
    private final Map<String, List<FeatureMetadata>> featuresByURI = new ConcurrentHashMap<>();
    private final Map<String, List<OperationMetadata>> operationsByURI = new ConcurrentHashMap<>();

    // Composite key indexes: "nsURI::name" -> versions
    private final Map<String, List<ClassMetadata>> classesByNsURIAndName = new ConcurrentHashMap<>();
    private final Map<String, List<ClassMetadata>> classesByNsURIAndInstanceClassName = new ConcurrentHashMap<>();

    // Global indexes for cross-package queries: name -> List<ClassMetadata>
    private final Map<String, List<ClassMetadata>> classesByName = new ConcurrentHashMap<>();
    private final Map<String, List<ClassMetadata>> classesByInstanceClassName = new ConcurrentHashMap<>();

    // ========================================================================
    // MetadataIndexWriter - Index operations
    // ========================================================================

    @Override
    public void indexPackage(PackageMetadata packageMetadata) {
        if (packageMetadata == null) {
            return;
        }
        for (ClassMetadata classMetadata : packageMetadata.getClasses()) {
            indexClass(classMetadata);
        }
    }

    @Override
    public void indexClass(ClassMetadata classMetadata) {
        if (classMetadata == null) {
            return;
        }

        String typeURI = classMetadata.getTypeURI();
        String name = classMetadata.getName();
        EClass eClass = classMetadata.getEClass();
        String nsURI = eClass != null && eClass.getEPackage() != null
            ? eClass.getEPackage().getNsURI()
            : null;
        String instanceClassName = eClass != null ? eClass.getInstanceClassName() : null;

        // Index by URI
        if (typeURI != null) {
            putMulti(classesByURI, typeURI, classMetadata);
        }

        // Index by nsURI + name
        if (nsURI != null && name != null) {
            putMulti(classesByNsURIAndName, compositeKey(nsURI, name), classMetadata);
        }

        // Index by nsURI + instanceClassName
        if (nsURI != null && instanceClassName != null) {
            putMulti(classesByNsURIAndInstanceClassName, compositeKey(nsURI, instanceClassName), classMetadata);
        }

        // Index globally by name
        if (name != null) {
            putMulti(classesByName, name, classMetadata);
        }

        // Index globally by instanceClassName
        if (instanceClassName != null) {
            putMulti(classesByInstanceClassName, instanceClassName, classMetadata);
        }

        // Index all features
        for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
            indexFeature(featureMetadata);
        }

        // Index all operations
        for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
            indexOperation(operationMetadata);
        }
    }

    @Override
    public void indexFeature(FeatureMetadata featureMetadata) {
        if (featureMetadata == null) {
            return;
        }
        EStructuralFeature eFeature = featureMetadata.getEFeature();
        if (eFeature != null) {
            String uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eFeature).toString();
            putMulti(featuresByURI, uri, featureMetadata);
        }
    }

    @Override
    public void indexOperation(OperationMetadata operationMetadata) {
        if (operationMetadata == null) {
            return;
        }
        EOperation eOperation = operationMetadata.getEOperation();
        if (eOperation != null) {
            String uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eOperation).toString();
            putMulti(operationsByURI, uri, operationMetadata);
        }
    }

    // ========================================================================
    // MetadataIndexWriter - Remove operations
    // ========================================================================

    @Override
    public void removePackage(PackageMetadata packageMetadata) {
        if (packageMetadata == null) {
            return;
        }
        for (ClassMetadata classMetadata : packageMetadata.getClasses()) {
            removeClass(classMetadata);
        }
    }

    @Override
    public void removeClass(ClassMetadata classMetadata) {
        if (classMetadata == null) {
            return;
        }

        String typeURI = classMetadata.getTypeURI();
        String name = classMetadata.getName();
        EClass eClass = classMetadata.getEClass();
        String nsURI = eClass != null && eClass.getEPackage() != null
            ? eClass.getEPackage().getNsURI()
            : null;
        String instanceClassName = eClass != null ? eClass.getInstanceClassName() : null;

        // Remove from URI index (only this version's entry — a surviving same-nsURI
        // version keeps its own, structurally identical, typeURI entry)
        if (typeURI != null) {
            removeMulti(classesByURI, typeURI, classMetadata);
        }

        // Remove from composite key indexes
        if (nsURI != null && name != null) {
            removeMulti(classesByNsURIAndName, compositeKey(nsURI, name), classMetadata);
        }
        if (nsURI != null && instanceClassName != null) {
            removeMulti(classesByNsURIAndInstanceClassName, compositeKey(nsURI, instanceClassName), classMetadata);
        }

        // Remove from global indexes
        if (name != null) {
            removeMulti(classesByName, name, classMetadata);
        }
        if (instanceClassName != null) {
            removeMulti(classesByInstanceClassName, instanceClassName, classMetadata);
        }

        // Remove all features
        for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
            removeFeature(featureMetadata);
        }

        // Remove all operations
        for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
            removeOperation(operationMetadata);
        }
    }

    @Override
    public void removeFeature(FeatureMetadata featureMetadata) {
        if (featureMetadata == null) {
            return;
        }
        EStructuralFeature eFeature = featureMetadata.getEFeature();
        if (eFeature != null) {
            String uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eFeature).toString();
            removeMulti(featuresByURI, uri, featureMetadata);
        }
    }

    @Override
    public void removeOperation(OperationMetadata operationMetadata) {
        if (operationMetadata == null) {
            return;
        }
        EOperation eOperation = operationMetadata.getEOperation();
        if (eOperation != null) {
            String uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eOperation).toString();
            removeMulti(operationsByURI, uri, operationMetadata);
        }
    }

    @Override
    public void clear() {
        classesByURI.clear();
        featuresByURI.clear();
        operationsByURI.clear();
        classesByNsURIAndName.clear();
        classesByNsURIAndInstanceClassName.clear();
        classesByName.clear();
        classesByInstanceClassName.clear();
    }

    // ========================================================================
    // MetadataIndexReader - Query operations
    // ========================================================================

    @Override
    public ClassMetadata findByInstanceClassName(String nsURI, String instanceClassName) {
        if (nsURI == null || instanceClassName == null) {
            return null;
        }
        return getLast(classesByNsURIAndInstanceClassName, compositeKey(nsURI, instanceClassName));
    }

    @Override
    public EList<ClassMetadata> findAllByInstanceClassName(String instanceClassName) {
        if (instanceClassName == null) {
            return new BasicEList<>();
        }
        List<ClassMetadata> results = classesByInstanceClassName.get(instanceClassName);
        return results != null ? new BasicEList<>(results) : new BasicEList<>();
    }

    @Override
    public ClassMetadata findByClassName(String nsURI, String className) {
        if (nsURI == null || className == null) {
            return null;
        }
        return getLast(classesByNsURIAndName, compositeKey(nsURI, className));
    }

    @Override
    public EList<ClassMetadata> findAllByClassName(String className) {
        if (className == null) {
            return new BasicEList<>();
        }
        List<ClassMetadata> results = classesByName.get(className);
        return results != null ? new BasicEList<>(results) : new BasicEList<>();
    }

    @Override
    public ClassMetadata findClassByURI(String uri) {
        if (uri == null) {
            return null;
        }
        return getLast(classesByURI, uri);
    }

    @Override
    public FeatureMetadata findFeatureByURI(String uri) {
        if (uri == null) {
            return null;
        }
        return getLast(featuresByURI, uri);
    }

    @Override
    public EList<ClassMetadata> findClassesByAnnotation(String annotationSource, String key, String value) {
        if (annotationSource == null || key == null) {
            return new BasicEList<>();
        }
        List<ClassMetadata> results = new ArrayList<>();
        for (List<ClassMetadata> versions : classesByURI.values()) {
            for (ClassMetadata classMetadata : versions) {
                EClass eClass = classMetadata.getEClass();
                if (eClass != null && hasAnnotation(eClass.getEAnnotation(annotationSource), key, value)) {
                    results.add(classMetadata);
                }
            }
        }
        return new BasicEList<>(results);
    }

    @Override
    public EList<FeatureMetadata> findFeaturesByAnnotation(String annotationSource, String key, String value) {
        if (annotationSource == null || key == null) {
            return new BasicEList<>();
        }
        List<FeatureMetadata> results = new ArrayList<>();
        for (List<FeatureMetadata> versions : featuresByURI.values()) {
            for (FeatureMetadata featureMetadata : versions) {
                EStructuralFeature eFeature = featureMetadata.getEFeature();
                if (eFeature != null && hasAnnotation(eFeature.getEAnnotation(annotationSource), key, value)) {
                    results.add(featureMetadata);
                }
            }
        }
        return new BasicEList<>(results);
    }

    @Override
    public OperationMetadata findOperationByURI(String uri) {
        if (uri == null) {
            return null;
        }
        return getLast(operationsByURI, uri);
    }

    @Override
    public EList<OperationMetadata> findOperationsByAnnotation(String annotationSource, String key, String value) {
        if (annotationSource == null || key == null) {
            return new BasicEList<>();
        }
        List<OperationMetadata> results = new ArrayList<>();
        for (List<OperationMetadata> versions : operationsByURI.values()) {
            for (OperationMetadata operationMetadata : versions) {
                EOperation eOperation = operationMetadata.getEOperation();
                if (eOperation != null && hasAnnotation(eOperation.getEAnnotation(annotationSource), key, value)) {
                    results.add(operationMetadata);
                }
            }
        }
        return new BasicEList<>(results);
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    /**
     * Creates a composite key from nsURI and name/className.
     */
    private String compositeKey(String nsURI, String name) {
        return nsURI + "::" + name;
    }

    /**
     * Appends a value to the per-key version list, creating the list on first use. The
     * list is a {@link CopyOnWriteArrayList} so concurrent readers (index lookups are
     * lock-free) always see a consistent snapshot while writers mutate under the service's
     * write lock.
     */
    private static <T> void putMulti(Map<String, List<T>> map, String key, T value) {
        map.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(value);
    }

    /**
     * Returns the most recently indexed value for a key, or {@code null} if none. Single-
     * result lookups pick the newest version — the same best-effort resolution the service
     * applies to name/URI lookups under same-nsURI multi-version registration. Iterates to
     * the last element (rather than index access) so a concurrent removal cannot race.
     */
    private static <T> T getLast(Map<String, List<T>> map, String key) {
        List<T> list = map.get(key);
        if (list == null) {
            return null;
        }
        T last = null;
        for (T value : list) {
            last = value;
        }
        return last;
    }

    /**
     * Removes a single value from the per-key version list, dropping the key when its last
     * version is gone. Removing one version never affects a surviving same-key version.
     */
    private static <T> void removeMulti(Map<String, List<T>> map, String key, T value) {
        List<T> list = map.get(key);
        if (list != null) {
            list.remove(value);
            if (list.isEmpty()) {
                map.remove(key);
            }
        }
    }

    /**
     * Checks if an annotation has the specified key and optionally value.
     *
     * @param annotation the annotation to check (may be null)
     * @param key the required key
     * @param value the required value, or null to match any value
     * @return true if the annotation has the key (and value if specified)
     */
    private boolean hasAnnotation(EAnnotation annotation, String key, String value) {
        if (annotation == null) {
            return false;
        }
        String actualValue = annotation.getDetails().get(key);
        if (actualValue == null) {
            return false;
        }
        // If value is null, match any value
        return value == null || value.equals(actualValue);
    }
}
