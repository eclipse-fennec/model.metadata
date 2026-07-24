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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.fennec.model.metadata.AttributeMetadata;
import org.eclipse.fennec.model.metadata.ClassAspect;
import org.eclipse.fennec.model.metadata.ClassMetadata;
import org.eclipse.fennec.model.metadata.ClassProfile;
import org.eclipse.fennec.model.metadata.FeatureAspect;
import org.eclipse.fennec.model.metadata.FeatureMetadata;
import org.eclipse.fennec.model.metadata.MetadataFactory;
import org.eclipse.fennec.model.metadata.MetadataRegistry;
import org.eclipse.fennec.model.metadata.OperationAspect;
import org.eclipse.fennec.model.metadata.OperationMetadata;
import org.eclipse.fennec.model.metadata.PackageAspect;
import org.eclipse.fennec.model.metadata.PackageMetadata;
import org.eclipse.fennec.model.metadata.PackageProfile;
import org.eclipse.fennec.model.metadata.ParameterMetadata;
import org.eclipse.fennec.model.metadata.ReferenceMetadata;
import org.eclipse.fennec.model.metadata.api.AspectProvider;
import org.eclipse.fennec.model.metadata.api.ArtifactStore;
import org.eclipse.fennec.model.metadata.api.FingerprintService;
import org.eclipse.fennec.model.metadata.api.MetadataHandler;
import org.eclipse.fennec.model.metadata.api.MetadataIndex;
import org.eclipse.fennec.model.metadata.api.MetadataIndexReader;
import org.eclipse.fennec.model.metadata.api.MetadataWhiteboard;

/**
 * Default implementation of the {@link MetadataWhiteboard}.
 * <p>
 * This service maintains a registry of pre-computed metadata for registered EPackages.
 * When an EPackage is registered, metadata is built for all its classes and features,
 * all registered AspectProviders are called to contribute their aspects, and then
 * each provider's buildProfiles is called with a filtered metadata copy.
 * </p>
 * <p>
 * Uses a {@link MetadataIndex} internally for fast indexed lookups. The index is
 * automatically updated when packages are registered or unregistered. The index
 * can be swapped at runtime via setMetadataIndex/unsetMetadataIndex (OSGi DS).
 * </p>
 *
 * @author Mark Hoffmann
 * @since 2025-12-09
 */
public class MetadataServiceImpl implements MetadataWhiteboard {

    private final MetadataRegistry registry;
    private final List<AspectProvider> aspectProviders = new CopyOnWriteArrayList<>();
    private final List<MetadataHandler> metadataHandlers = new CopyOnWriteArrayList<>();
    private volatile MetadataIndex index;

    // Primary registry: canonical modelFingerprint -> metadata. One entry per model VERSION —
    // two diverging versions of the same nsURI coexist; identical content dedupes onto one entry.
    private final Map<String, PackageMetadata> packagesByFingerprint = new ConcurrentHashMap<>();

    // Secondary, best-effort index: nsURI -> versions in registration order (last = newest).
    // Name-based lookups are inherently ambiguous under multi-version registration; exact
    // resolution goes through the fingerprint.
    private final Map<String, List<PackageMetadata>> packagesByNsURI = new ConcurrentHashMap<>();

    // Whiteboard liveness per fingerprint: number of registerPackage calls not yet undone.
    // Entries created by the pull path (getPackageMetadata(EPackage)) carry no count and are
    // never removed by unregisterPackage.
    private final Map<String, Integer> livenessByFingerprint = new ConcurrentHashMap<>();

    // Memoized fingerprint per EPackage instance for the hot read path. Write paths
    // (register/unregister) always compute fresh and refresh this memo; a mutation of a
    // package that is never re-registered is deliberately not tracked.
    private final Map<EPackage, String> fingerprintByInstance = Collections.synchronizedMap(new WeakHashMap<>());

    // Fast lookup maps for EClass/EStructuralFeature -> Metadata (not indexed by string)
    private final Map<EClass, ClassMetadata> classesByEClass = new ConcurrentHashMap<>();
    private final Map<EStructuralFeature, FeatureMetadata> featuresByEFeature = new ConcurrentHashMap<>();
    private final Map<EOperation, OperationMetadata> operationsByEOperation = new ConcurrentHashMap<>();

    // Guards the shared, non-thread-safe registry.getPackages() EList and the compound
    // build/rebuild/attach sequences. Structural mutations (register/unregister of
    // packages, providers, index, handlers) take the write lock; readers that iterate
    // EMF aspect/profile lists take the read lock. Hot-path lookups go through the
    // ConcurrentHashMaps above and stay lock-free.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Computes the cached per-package modelFingerprint. Stateless and cheap; defaults to
    // the built-in implementation and can be replaced (e.g. injected by the OSGi component).
    private volatile FingerprintService fingerprintService = new DefaultFingerprintService();

    // Optional durable store for derived artifacts (profiles). When present, profiles are
    // resolved-or-built (reused across re-registration instead of rebuilt). When null, the
    // service always builds (backward-compatible behavior).
    private volatile ArtifactStore artifactStore;

    /**
     * Creates a new MetadataServiceImpl with an empty registry and default Map-based index.
     */
    public MetadataServiceImpl() {
        this(new MapBasedMetadataIndex());
    }

    /**
     * Creates a new MetadataServiceImpl with an empty registry and the specified index.
     *
     * @param index the metadata index to use
     */
    public MetadataServiceImpl(MetadataIndex index) {
        this.registry = MetadataFactory.eINSTANCE.createMetadataRegistry();
        this.index = index;
    }

    /**
     * Creates a new MetadataServiceImpl with the given registry and default Map-based index.
     * Use this constructor to load a pre-computed registry.
     *
     * @param registry the pre-computed registry
     */
    public MetadataServiceImpl(MetadataRegistry registry) {
        this(registry, new MapBasedMetadataIndex());
    }

    /**
     * Creates a new MetadataServiceImpl with the given registry and index.
     * Use this constructor to load a pre-computed registry with a custom index.
     *
     * @param registry the pre-computed registry
     * @param index the metadata index to use
     */
    public MetadataServiceImpl(MetadataRegistry registry, MetadataIndex index) {
        this.registry = registry;
        this.index = index;
        // Rebuild lookup maps and index from registry
        rebuildLookupMaps();
    }

    // ========================================================================
    // MetadataService (consumer read-only) methods
    // ========================================================================

    @Override
    public MetadataIndexReader getIndexReader() {
        return index;
    }

    @Override
    public PackageMetadata getPackageMetadata(String nsURI) {
        if (nsURI == null) {
            return null;
        }
        List<PackageMetadata> versions = packagesByNsURI.get(nsURI);
        if (versions == null) {
            return null;
        }
        // Best effort under multi-version ambiguity: the most recently registered version.
        PackageMetadata last = null;
        for (PackageMetadata version : versions) {
            last = version;
        }
        return last;
    }

    @Override
    public PackageMetadata getPackageMetadata(EPackage ePackage) {
        if (ePackage == null) {
            return null;
        }
        // Lock-free fast path for known content.
        PackageMetadata existing = packagesByFingerprint.get(memoizedFingerprint(ePackage));
        if (existing != null) {
            return existing;
        }
        lock.writeLock().lock();
        try {
            // Recompute fresh under the lock (the memo may predate a content change).
            String fp = fingerprintFor(ePackage);
            PackageMetadata pkgMetadata = packagesByFingerprint.get(fp);
            if (pkgMetadata != null) {
                return pkgMetadata;
            }
            // Pull path: build-and-cache without touching whiteboard liveness — a memoized
            // read, not a registration; unregisterPackage never evicts pull-created entries.
            return buildAndRegister(ePackage, fp, null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public PackageMetadata getPackageMetadataByFingerprint(String fingerprint) {
        return fingerprint != null ? packagesByFingerprint.get(fingerprint) : null;
    }

    @Override
    public EList<PackageMetadata> getPackageMetadataVersions(String nsURI) {
        if (nsURI == null) {
            return new BasicEList<>();
        }
        List<PackageMetadata> versions = packagesByNsURI.get(nsURI);
        // Defensive snapshot in registration order (oldest first, newest last — the tail is
        // exactly what getPackageMetadata(String) serves as its best-effort newest). Empty
        // when the nsURI is unknown. Exposes the full candidate set; selection stays with
        // the caller.
        return versions != null ? new BasicEList<>(versions) : new BasicEList<>();
    }

    @Override
    public ClassMetadata getClassMetadata(EClass eClass) {
        if (eClass == null) {
            return null;
        }
        return classesByEClass.get(eClass);
    }

    @Override
    public ClassMetadata getClassMetadataByURI(String uri) {
        MetadataIndex idx = this.index;
        return idx != null ? idx.findClassByURI(uri) : null;
    }

    @Override
    public ClassMetadata getClassMetadataByName(String className, String nsURI) {
        MetadataIndex idx = this.index;
        return idx != null ? idx.findByClassName(nsURI, className) : null;
    }

    @Override
    public FeatureMetadata getFeatureMetadata(EStructuralFeature feature) {
        if (feature == null) {
            return null;
        }
        return featuresByEFeature.get(feature);
    }

    @Override
    public OperationMetadata getOperationMetadata(EOperation operation) {
        if (operation == null) {
            return null;
        }
        return operationsByEOperation.get(operation);
    }

    @Override
    public OperationMetadata getOperationMetadataByURI(String uri) {
        MetadataIndex idx = this.index;
        return idx != null ? idx.findOperationByURI(uri) : null;
    }

    @Override
    public OperationMetadata getOperationMetadataFromClass(String operationName, ClassMetadata classMetadata) {
        if (operationName == null || classMetadata == null) {
            return null;
        }
        // Operation names are not unique when operations are overloaded; return the first match.
        for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
            if (operationName.equals(operationMetadata.getName())) {
                return operationMetadata;
            }
        }
        return null;
    }

    @Override
    public FeatureMetadata getFeatureMetadataByURI(String uri) {
        MetadataIndex idx = this.index;
        return idx != null ? idx.findFeatureByURI(uri) : null;
    }

    @Override
    public FeatureMetadata getFeatureMetadataByName(String featureName, String className, String nsURI) {
        ClassMetadata classMetadata = getClassMetadataByName(className, nsURI);
        if (classMetadata != null) {
            return getFeatureMetadataFromClass(featureName, classMetadata);
        }
        return null;
    }

    @Override
    public FeatureMetadata getFeatureMetadataFromClass(String featureName, ClassMetadata classMetadata) {
        if (classMetadata == null || featureName == null) {
            return null;
        }
        for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
            if (featureName.equals(featureMetadata.getName())) {
                return featureMetadata;
            }
        }
        return null;
    }

    @Override
    public PackageAspect getPackageAspect(EPackage ePackage, String aspectTypeId) {
        if (ePackage == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            PackageMetadata pkgMetadata = resolveRegistered(ePackage);
            if (pkgMetadata != null) {
                for (PackageAspect aspect : pkgMetadata.getAspects()) {
                    if (aspectTypeId.equals(aspect.getTypeId())) {
                        return aspect;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ClassAspect getClassAspect(EClass eClass, String aspectTypeId) {
        lock.readLock().lock();
        try {
            ClassMetadata classMetadata = getClassMetadata(eClass);
            if (classMetadata != null) {
                for (ClassAspect aspect : classMetadata.getAspects()) {
                    if (aspectTypeId.equals(aspect.getTypeId())) {
                        return aspect;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public FeatureAspect getFeatureAspect(EStructuralFeature feature, String aspectTypeId) {
        lock.readLock().lock();
        try {
            FeatureMetadata featureMetadata = getFeatureMetadata(feature);
            if (featureMetadata != null) {
                for (FeatureAspect aspect : featureMetadata.getAspects()) {
                    if (aspectTypeId.equals(aspect.getTypeId())) {
                        return aspect;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public OperationAspect getOperationAspect(EOperation operation, String aspectTypeId) {
        lock.readLock().lock();
        try {
            OperationMetadata operationMetadata = getOperationMetadata(operation);
            if (operationMetadata != null) {
                for (OperationAspect aspect : operationMetadata.getAspects()) {
                    if (aspectTypeId.equals(aspect.getTypeId())) {
                        return aspect;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public PackageProfile getPackageProfile(EPackage ePackage, String typeId) {
        if (ePackage == null || typeId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            PackageMetadata pkgMetadata = resolveRegistered(ePackage);
            return findPackageProfile(pkgMetadata, typeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public PackageProfile getPackageProfileByNsURI(String nsURI, String typeId) {
        if (nsURI == null || typeId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            PackageMetadata pkgMetadata = getPackageMetadata(nsURI);
            return findPackageProfile(pkgMetadata, typeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ClassProfile getClassProfile(EClass eClass, String typeId) {
        if (eClass == null || typeId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            ClassMetadata classMetadata = classesByEClass.get(eClass);
            if (classMetadata == null) {
                return null;
            }
            PackageMetadata pkgMetadata = classMetadata.getPackage();
            PackageProfile pkgProfile = findPackageProfile(pkgMetadata, typeId);
            if (pkgProfile != null) {
                for (ClassProfile cp : pkgProfile.getClassProfiles()) {
                    if (eClass.equals(cp.getEClass())) {
                        return cp;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ClassProfile getClassProfileByURI(String eClassURI, String typeId) {
        if (eClassURI == null || typeId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            MetadataIndex idx = this.index;
            ClassMetadata classMetadata = idx != null ? idx.findClassByURI(eClassURI) : null;
            if (classMetadata == null) {
                return null;
            }
            PackageMetadata pkgMetadata = classMetadata.getPackage();
            PackageProfile pkgProfile = findPackageProfile(pkgMetadata, typeId);
            if (pkgProfile != null) {
                EClass eClass = classMetadata.getEClass();
                for (ClassProfile cp : pkgProfile.getClassProfiles()) {
                    if (eClass.equals(cp.getEClass())) {
                        return cp;
                    }
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public MetadataRegistry getRegistry() {
        return registry;
    }

    // ========================================================================
    // MetadataWhiteboard (admin/lifecycle) methods
    // ========================================================================

    @Override
    public PackageMetadata registerPackage(EPackage ePackage) {
        return registerPackage(ePackage, null);
    }

    /**
     * Sets the {@link FingerprintService} used to compute the cached per-package
     * {@code modelFingerprint}. Defaults to the built-in implementation.
     *
     * @param fingerprintService the service to use (ignored if {@code null})
     */
    public void setFingerprintService(FingerprintService fingerprintService) {
        if (fingerprintService != null) {
            this.fingerprintService = fingerprintService;
            // Memoized values were computed with the previous service/scheme.
            fingerprintByInstance.clear();
        }
    }

    /**
     * Computes the model fingerprint of this instance freshly and refreshes the per-instance
     * memo. Write paths (register/unregister) must use this — a memoized value may predate a
     * content change of the (mutable) EPackage.
     */
    private String fingerprintFor(EPackage ePackage) {
        String fp = fingerprintService.fingerprint(ePackage);
        fingerprintByInstance.put(ePackage, fp);
        return fp;
    }

    /** Memoized fingerprint for hot read paths; computes (and memoizes) on first sight. */
    private String memoizedFingerprint(EPackage ePackage) {
        String fp = fingerprintByInstance.get(ePackage);
        return fp != null ? fp : fingerprintFor(ePackage);
    }

    /**
     * Resolves the registered metadata for exactly this instance's model version (by
     * fingerprint, never builds). Falls back to the nsURI index only for legacy entries
     * that were loaded without a modelFingerprint, and only when unambiguous — the
     * fallback can never cross two live versions.
     */
    private PackageMetadata resolveRegistered(EPackage ePackage) {
        PackageMetadata pkgMetadata = packagesByFingerprint.get(memoizedFingerprint(ePackage));
        if (pkgMetadata != null) {
            return pkgMetadata;
        }
        List<PackageMetadata> versions = packagesByNsURI.get(ePackage.getNsURI());
        if (versions != null && versions.size() == 1) {
            PackageMetadata only = versions.iterator().next();
            if (only.getModelFingerprint() == null) {
                return only;
            }
        }
        return null;
    }

    /**
     * Sets the {@link ArtifactStore} used to persist and reuse derived profiles. When set,
     * profile building becomes resolve-or-build (a stored profile for the same
     * {@code (modelFingerprint, typeId)} is reused instead of rebuilt). Pass {@code null}
     * to disable (always-build).
     *
     * @param artifactStore the store, or {@code null} to disable reuse
     */
    public void setArtifactStore(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * Registers an EPackage, additionally taking the OSGi service properties of its
     * EPackage service as transient build context. The properties are exposed to
     * AspectProviders via {@link PackageMetadata#getProperties()} (stringified, never
     * serialized) so providers can decide relevance; the model fingerprint is computed
     * locally and cached on the PackageMetadata. Not part of the generated
     * MetadataWhiteboard API.
     *
     * @param ePackage the package to register (may be {@code null})
     * @param properties the EPackage service properties, or {@code null} if none
     * @return the new or existing PackageMetadata, or {@code null} if {@code ePackage} is null
     */
    @Override
    public PackageMetadata registerPackage(EPackage ePackage, Map<String, Object> properties) {
        if (ePackage == null) {
            return null;
        }

        lock.writeLock().lock();
        try {
            // The fingerprint is computed BEFORE any existence check: registration is keyed
            // by model version, not by nsURI. Same content -> dedupe onto the existing entry;
            // diverging content under the same nsURI -> a coexisting second entry (never a
            // silent no-op that would serve one version's objects with another's metadata).
            String fp = fingerprintFor(ePackage);
            PackageMetadata pkgMetadata = packagesByFingerprint.get(fp);
            if (pkgMetadata == null) {
                pkgMetadata = buildAndRegister(ePackage, fp, properties);
            }
            livenessByFingerprint.merge(fp, 1, Integer::sum);
            return pkgMetadata;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Builds the full PackageMetadata for a model version and adds it to the registry and
     * all lookup structures. Must be called under the write lock with a freshly computed
     * fingerprint. Does NOT touch whiteboard liveness — callers decide whether the entry
     * counts as a registration (whiteboard path) or as a cached read (pull path).
     */
    private PackageMetadata buildAndRegister(EPackage ePackage, String fp, Map<String, Object> properties) {
        String nsURI = ePackage.getNsURI();

        // Create package metadata
        PackageMetadata pkgMetadata = MetadataFactory.eINSTANCE.createPackageMetadata();
        pkgMetadata.setEPackage(ePackage);
        pkgMetadata.setNsURI(nsURI);

        // Local, reproducible model fingerprint = join key to the EPackage registry.
        // An externally supplied fingerprint (if any) arrives among the service
        // properties below; it is captured as build context but NOT trusted here —
        // the local computation is authoritative.
        pkgMetadata.setModelFingerprint(fp);

        // Transient build context: capture the EPackage service properties (stringified).
        // Not serialized (the feature is transient) — providers use it for relevance.
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    pkgMetadata.getProperties().put(entry.getKey(), stringifyProperty(entry.getValue()));
                }
            }
        }

        // Apply all aspect providers to package
        for (AspectProvider provider : aspectProviders) {
            PackageAspect aspect = provider.buildPackageAspect(pkgMetadata);
            if (aspect != null) {
                aspect.setTypeId(provider.getAspectTypeId());
                pkgMetadata.getAspects().add(aspect);
            }
        }

        // Process all EClasses
        for (EClassifier classifier : ePackage.getEClassifiers()) {
            if (classifier instanceof EClass eClass) {
                ClassMetadata classMetadata = buildClassMetadata(eClass, pkgMetadata);
                pkgMetadata.getClasses().add(classMetadata);
            }
        }

        // Resolve cross-references (supertypes, target classes)
        resolveReferences(pkgMetadata);

        // Build profiles for each provider
        buildProfilesForAllProviders(pkgMetadata);

        // Add to registry and lookup maps
        registry.getPackages().add(pkgMetadata);
        packagesByFingerprint.put(fp, pkgMetadata);
        packagesByNsURI.computeIfAbsent(nsURI, k -> new CopyOnWriteArrayList<>()).add(pkgMetadata);

        // Index the package
        MetadataIndex idx = this.index;
        if (idx != null) {
            idx.indexPackage(pkgMetadata);
        }

        // Notify metadata handlers
        for (MetadataHandler handler : metadataHandlers) {
            handler.onPackageRegistered(pkgMetadata);
        }

        return pkgMetadata;
    }

    @Override
    public void unregisterPackage(EPackage ePackage) {
        if (ePackage == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            // Liveness is per model version (fingerprint), never per nsURI: only the last
            // whiteboard registration of THIS fingerprint removes the entry. Another live
            // version of the same nsURI — or a pull-created cache entry, which carries no
            // liveness count — is never affected by this unbind.
            String fp = fingerprintFor(ePackage);
            PackageMetadata pkgMetadata = packagesByFingerprint.get(fp);
            if (pkgMetadata == null) {
                return;
            }
            Integer count = livenessByFingerprint.get(fp);
            if (count == null) {
                return; // pull-created cache entry — there is no registration to undo
            }
            if (count > 1) {
                livenessByFingerprint.put(fp, count - 1);
                return;
            }
            livenessByFingerprint.remove(fp);

            {
                // Notify metadata handlers before removing
                for (MetadataHandler handler : metadataHandlers) {
                    handler.onPackageUnregistered(pkgMetadata);
                }

                packagesByFingerprint.remove(fp);
                List<PackageMetadata> versions = packagesByNsURI.get(pkgMetadata.getNsURI());
                if (versions != null) {
                    versions.remove(pkgMetadata);
                    if (versions.isEmpty()) {
                        packagesByNsURI.remove(pkgMetadata.getNsURI(), versions);
                    }
                }

                // Remove from index first
                MetadataIndex idx = this.index;
                if (idx != null) {
                    idx.removePackage(pkgMetadata);
                }

                // Remove from lookup maps
                for (ClassMetadata classMetadata : pkgMetadata.getClasses()) {
                    EClass eClass = classMetadata.getEClass();
                    if (eClass != null) {
                        classesByEClass.remove(eClass);
                    }

                    for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                        EStructuralFeature feature = featureMetadata.getEFeature();
                        if (feature != null) {
                            featuresByEFeature.remove(feature);
                        }
                    }

                    for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                        EOperation operation = operationMetadata.getEOperation();
                        if (operation != null) {
                            operationsByEOperation.remove(operation);
                        }
                    }
                }

                // Remove from registry
                registry.getPackages().remove(pkgMetadata);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void registerAspectProvider(AspectProvider provider) {
        if (provider == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (!aspectProviders.contains(provider)) {
                aspectProviders.add(provider);

                // Apply provider to all existing metadata and build profiles
                for (PackageMetadata pkgMetadata : registry.getPackages()) {
                    applyProviderToPackage(provider, pkgMetadata);
                    buildProfilesForProvider(provider, pkgMetadata);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void unregisterAspectProvider(AspectProvider provider) {
        if (provider == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            aspectProviders.remove(provider);

            // Remove aspects and profiles from this provider
            String typeId = provider.getAspectTypeId();
            for (PackageMetadata pkgMetadata : registry.getPackages()) {
                removeAspectsFromPackage(typeId, pkgMetadata);
                removeProfileFromPackage(typeId, pkgMetadata);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public EList<AspectProvider> getAspectProviders() {
        return new BasicEList<>(aspectProviders);
    }

    @Override
    public MetadataIndex getMetadataIndex() {
        return index;
    }

    @Override
    public void setMetadataIndex(MetadataIndex index) {
        lock.writeLock().lock();
        try {
            this.index = index;
            if (index != null) {
                // Populate the new index with all existing metadata
                for (PackageMetadata pkgMetadata : registry.getPackages()) {
                    index.indexPackage(pkgMetadata);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void unsetMetadataIndex(MetadataIndex index) {
        lock.writeLock().lock();
        try {
            if (index != null && index == this.index) {
                index.clear();
                this.index = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addMetadataHandler(MetadataHandler handler) {
        if (handler == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (!metadataHandlers.contains(handler)) {
                metadataHandlers.add(handler);

                // Late binding: notify handler about all existing packages
                for (PackageMetadata pkgMetadata : registry.getPackages()) {
                    handler.onPackageRegistered(pkgMetadata);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void removeMetadataHandler(MetadataHandler handler) {
        if (handler == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            metadataHandlers.remove(handler);
            handler.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========================================================================
    // Private helper methods
    // ========================================================================

    private ClassMetadata buildClassMetadata(EClass eClass, PackageMetadata pkgMetadata) {
        ClassMetadata classMetadata = MetadataFactory.eINSTANCE.createClassMetadata();
        classMetadata.setEClass(eClass);
        classMetadata.setName(eClass.getName());
        classMetadata.setClassifierID(eClass.getClassifierID());
        classMetadata.setTypeURI(EcoreUtil.getURI(eClass).toString());

        // Determine if class has ID
        EAttribute idAttribute = eClass.getEIDAttribute();
        classMetadata.setHasId(idAttribute != null);

        // Process features first (before class aspects — providers may need feature metadata)
        for (EStructuralFeature feature : eClass.getEStructuralFeatures()) {
            FeatureMetadata featureMetadata = buildFeatureMetadata(feature, classMetadata);
            classMetadata.getFeatures().add(featureMetadata);

            // Track ID features
            if (feature instanceof EAttribute attr && attr.isID()) {
                classMetadata.getIdFeatures().add(featureMetadata);
            }
        }

        // Process operations (before class aspects — providers may need operation metadata)
        for (EOperation operation : eClass.getEOperations()) {
            OperationMetadata operationMetadata = buildOperationMetadata(operation, classMetadata);
            classMetadata.getOperations().add(operationMetadata);
        }

        // Add to EClass lookup map
        classesByEClass.put(eClass, classMetadata);

        // Apply all aspect providers (after features are built)
        for (AspectProvider provider : aspectProviders) {
            ClassAspect aspect = provider.buildClassAspect(classMetadata);
            if (aspect != null) {
                aspect.setTypeId(provider.getAspectTypeId());
                classMetadata.getAspects().add(aspect);
            }
        }

        return classMetadata;
    }

    private FeatureMetadata buildFeatureMetadata(EStructuralFeature feature, ClassMetadata classMetadata) {
        FeatureMetadata featureMetadata;

        if (feature instanceof EAttribute attr) {
            AttributeMetadata attrMetadata = MetadataFactory.eINSTANCE.createAttributeMetadata();
            attrMetadata.setEAttribute(attr);
            attrMetadata.setIsId(attr.isID());
            attrMetadata.setDefaultValue(attr.getDefaultValue());
            featureMetadata = attrMetadata;
        } else if (feature instanceof EReference ref) {
            ReferenceMetadata refMetadata = MetadataFactory.eINSTANCE.createReferenceMetadata();
            refMetadata.setEReference(ref);
            refMetadata.setContainment(ref.isContainment());
            refMetadata.setHasBidirectional(ref.getEOpposite() != null);
            featureMetadata = refMetadata;
        } else {
            // Should not happen, but handle gracefully
            return null;
        }

        // Common properties
        featureMetadata.setEFeature(feature);
        featureMetadata.setName(feature.getName());
        featureMetadata.setFeatureID(feature.getFeatureID());
        featureMetadata.setExtendedMetaDataName(getExtendedMetaDataName(feature));

        // Add to EFeature lookup map
        featuresByEFeature.put(feature, featureMetadata);

        // Apply all aspect providers
        for (AspectProvider provider : aspectProviders) {
            FeatureAspect aspect;
            if (featureMetadata instanceof AttributeMetadata attrMd) {
                aspect = provider.buildAttributeAspect(attrMd);
            } else if (featureMetadata instanceof ReferenceMetadata refMd) {
                aspect = provider.buildReferenceAspect(refMd);
            } else {
                aspect = provider.buildFeatureAspect(featureMetadata);
            }

            if (aspect != null) {
                aspect.setTypeId(provider.getAspectTypeId());
                featureMetadata.getAspects().add(aspect);
            }
        }

        return featureMetadata;
    }

    private OperationMetadata buildOperationMetadata(EOperation operation, ClassMetadata classMetadata) {
        OperationMetadata operationMetadata = MetadataFactory.eINSTANCE.createOperationMetadata();
        operationMetadata.setEOperation(operation);
        operationMetadata.setName(operation.getName());
        operationMetadata.setOperationID(operation.getOperationID());

        // Build parameter metadata (types resolved later in resolveReferences)
        for (EParameter parameter : operation.getEParameters()) {
            ParameterMetadata parameterMetadata = MetadataFactory.eINSTANCE.createParameterMetadata();
            parameterMetadata.setEParameter(parameter);
            parameterMetadata.setName(parameter.getName());
            operationMetadata.getParameters().add(parameterMetadata);
        }

        // Add to EOperation lookup map
        operationsByEOperation.put(operation, operationMetadata);

        // Apply all aspect providers
        for (AspectProvider provider : aspectProviders) {
            OperationAspect aspect = provider.buildOperationAspect(operationMetadata);
            if (aspect != null) {
                aspect.setTypeId(provider.getAspectTypeId());
                operationMetadata.getAspects().add(aspect);
            }
        }

        return operationMetadata;
    }

    private void resolveReferences(PackageMetadata pkgMetadata) {
        for (ClassMetadata classMetadata : pkgMetadata.getClasses()) {
            EClass eClass = classMetadata.getEClass();

            // Resolve supertypes
            for (EClass superType : eClass.getESuperTypes()) {
                ClassMetadata superMetadata = classesByEClass.get(superType);
                if (superMetadata != null) {
                    classMetadata.getSuperTypes().add(superMetadata);
                }
            }

            // Resolve all supertypes (transitive)
            for (EClass superType : eClass.getEAllSuperTypes()) {
                ClassMetadata superMetadata = classesByEClass.get(superType);
                if (superMetadata != null) {
                    classMetadata.getAllSuperTypes().add(superMetadata);
                }
            }

            // Resolve reference targets
            for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                if (featureMetadata instanceof ReferenceMetadata refMetadata) {
                    EReference ref = refMetadata.getEReference();

                    // Target class
                    EClass targetClass = ref.getEReferenceType();
                    ClassMetadata targetMetadata = classesByEClass.get(targetClass);
                    refMetadata.setTargetClassMetadata(targetMetadata);

                    // Opposite reference
                    EReference opposite = ref.getEOpposite();
                    if (opposite != null) {
                        FeatureMetadata oppositeMetadata = featuresByEFeature.get(opposite);
                        if (oppositeMetadata instanceof ReferenceMetadata oppRefMetadata) {
                            refMetadata.setOppositeMetadata(oppRefMetadata);
                        }
                    }
                }
            }

            // Resolve operation return-type and parameter-type metadata (only when the
            // type is an EClass in a registered package; null otherwise, e.g. void /
            // EDataType returns or parameters).
            for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                EClassifier returnType = operationMetadata.getEOperation().getEType();
                if (returnType instanceof EClass returnClass) {
                    operationMetadata.setReturnTypeMetadata(classesByEClass.get(returnClass));
                }

                for (ParameterMetadata parameterMetadata : operationMetadata.getParameters()) {
                    EClassifier paramType = parameterMetadata.getEParameter().getEType();
                    if (paramType instanceof EClass paramClass) {
                        parameterMetadata.setTypeMetadata(classesByEClass.get(paramClass));
                    }
                }
            }
        }
    }

    private void buildProfilesForAllProviders(PackageMetadata pkgMetadata) {
        for (AspectProvider provider : aspectProviders) {
            buildProfilesForProvider(provider, pkgMetadata);
        }
    }

    private void buildProfilesForProvider(AspectProvider provider, PackageMetadata pkgMetadata) {
        String typeId = provider.getAspectTypeId();

        // Resolve-or-build: if a store holds a profile for this model version + provider,
        // reuse it instead of rebuilding (mediator role). The key is the local
        // modelFingerprint (join key); provider-specific derivation inputs may extend it
        // in the future. Reuse decisions never trust an externally supplied fingerprint.
        ArtifactStore store = this.artifactStore;
        String fp = pkgMetadata.getModelFingerprint();
        if (store != null && fp != null) {
            Optional<EObject> hit = store.resolve(fp, typeId);
            if (hit.isPresent() && hit.get() instanceof PackageProfile reused) {
                reused.setTypeId(typeId);
                pkgMetadata.getProfiles().add(reused);
                return; // reused — no rebuild
            }
        }

        // Filtered copy for the provider: metadata + only this provider's aspects, and NO
        // profiles. Profiles are outputs — a provider must not see other providers' profiles,
        // and copying them is unnecessary. Temporarily detach profiles for the copy; we hold
        // the write lock, so this transient change is not observable.
        PackageMetadata filteredCopy;
        java.util.List<PackageProfile> detachedProfiles = new java.util.ArrayList<>(pkgMetadata.getProfiles());
        pkgMetadata.getProfiles().clear();
        try {
            filteredCopy = EcoreUtil.copy(pkgMetadata);
            filterAspectsByTypeId(filteredCopy, typeId);
        } catch (IllegalArgumentException e) {
            // EcoreUtil.copy fails if aspect classes are abstract (e.g., in tests with
            // non-EMF-registered aspect subclasses). In production, concrete EMF aspect
            // classes (e.g., ClassCodecAspect) will work fine.
            return;
        } finally {
            pkgMetadata.getProfiles().addAll(detachedProfiles);
        }

        PackageProfile profile = provider.buildProfiles(filteredCopy);
        if (profile != null) {
            profile.setTypeId(typeId);
            pkgMetadata.getProfiles().add(profile);
            if (store != null && fp != null) {
                store.put(fp, typeId, profile);
            }
        }
    }

    /**
     * Removes all aspects from the copied metadata tree whose typeId does NOT match
     * the given typeId. This ensures the provider sees only its own aspects.
     */
    private void filterAspectsByTypeId(PackageMetadata copy, String typeId) {
        copy.getAspects().removeIf(a -> !typeId.equals(a.getTypeId()));
        for (ClassMetadata classMetadata : copy.getClasses()) {
            classMetadata.getAspects().removeIf(a -> !typeId.equals(a.getTypeId()));
            for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                featureMetadata.getAspects().removeIf(a -> !typeId.equals(a.getTypeId()));
            }
            for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                operationMetadata.getAspects().removeIf(a -> !typeId.equals(a.getTypeId()));
            }
        }
    }

    private void applyProviderToPackage(AspectProvider provider, PackageMetadata pkgMetadata) {
        // Build package aspect
        PackageAspect pkgAspect = provider.buildPackageAspect(pkgMetadata);
        if (pkgAspect != null) {
            pkgAspect.setTypeId(provider.getAspectTypeId());
            pkgMetadata.getAspects().add(pkgAspect);
        }

        for (ClassMetadata classMetadata : pkgMetadata.getClasses()) {
            // Build feature aspects first (before class aspects)
            for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                FeatureAspect featureAspect;

                if (featureMetadata instanceof AttributeMetadata attrMd) {
                    featureAspect = provider.buildAttributeAspect(attrMd);
                } else if (featureMetadata instanceof ReferenceMetadata refMd) {
                    featureAspect = provider.buildReferenceAspect(refMd);
                } else {
                    featureAspect = provider.buildFeatureAspect(featureMetadata);
                }

                if (featureAspect != null) {
                    featureAspect.setTypeId(provider.getAspectTypeId());
                    featureMetadata.getAspects().add(featureAspect);
                }
            }

            // Build operation aspects
            for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                OperationAspect operationAspect = provider.buildOperationAspect(operationMetadata);
                if (operationAspect != null) {
                    operationAspect.setTypeId(provider.getAspectTypeId());
                    operationMetadata.getAspects().add(operationAspect);
                }
            }

            // Build class aspect (after features)
            ClassAspect classAspect = provider.buildClassAspect(classMetadata);
            if (classAspect != null) {
                classAspect.setTypeId(provider.getAspectTypeId());
                classMetadata.getAspects().add(classAspect);
            }
        }
    }

    private void removeAspectsFromPackage(String typeId, PackageMetadata pkgMetadata) {
        // Remove package aspects
        pkgMetadata.getAspects().removeIf(a -> typeId.equals(a.getTypeId()));

        for (ClassMetadata classMetadata : pkgMetadata.getClasses()) {
            classMetadata.getAspects().removeIf(a -> typeId.equals(a.getTypeId()));

            for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                featureMetadata.getAspects().removeIf(a -> typeId.equals(a.getTypeId()));
            }

            for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                operationMetadata.getAspects().removeIf(a -> typeId.equals(a.getTypeId()));
            }
        }
    }

    private void removeProfileFromPackage(String typeId, PackageMetadata pkgMetadata) {
        pkgMetadata.getProfiles().removeIf(p -> typeId.equals(p.getTypeId()));
    }

    private PackageProfile findPackageProfile(PackageMetadata pkgMetadata, String typeId) {
        if (pkgMetadata == null) {
            return null;
        }
        for (PackageProfile profile : pkgMetadata.getProfiles()) {
            if (typeId.equals(profile.getTypeId())) {
                return profile;
            }
        }
        return null;
    }

    /** Stringifies an OSGi service-property value for the transient properties bag. */
    private static String stringifyProperty(Object value) {
        if (value instanceof Object[] array) {
            return java.util.Arrays.toString(array);
        }
        return String.valueOf(value);
    }

    private void rebuildLookupMaps() {
        packagesByFingerprint.clear();
        packagesByNsURI.clear();
        livenessByFingerprint.clear();
        fingerprintByInstance.clear();
        classesByEClass.clear();
        featuresByEFeature.clear();
        operationsByEOperation.clear();
        MetadataIndex idx = this.index;
        if (idx != null) {
            idx.clear();
        }

        for (PackageMetadata pkgMetadata : registry.getPackages()) {
            packagesByNsURI.computeIfAbsent(pkgMetadata.getNsURI(), k -> new CopyOnWriteArrayList<>())
                    .add(pkgMetadata);
            String fp = pkgMetadata.getModelFingerprint();
            if (fp != null) {
                packagesByFingerprint.put(fp, pkgMetadata);
                // Loaded entries are live by construction: one unregister removes them,
                // matching the pre-multi-version behavior for loaded registries.
                livenessByFingerprint.put(fp, 1);
                EPackage ePackage = pkgMetadata.getEPackage();
                if (ePackage != null) {
                    fingerprintByInstance.put(ePackage, fp);
                }
            }

            for (ClassMetadata classMetadata : pkgMetadata.getClasses()) {
                EClass eClass = classMetadata.getEClass();
                if (eClass != null) {
                    classesByEClass.put(eClass, classMetadata);
                }

                for (FeatureMetadata featureMetadata : classMetadata.getFeatures()) {
                    EStructuralFeature feature = featureMetadata.getEFeature();
                    if (feature != null) {
                        featuresByEFeature.put(feature, featureMetadata);
                    }
                }

                for (OperationMetadata operationMetadata : classMetadata.getOperations()) {
                    EOperation operation = operationMetadata.getEOperation();
                    if (operation != null) {
                        operationsByEOperation.put(operation, operationMetadata);
                    }
                }
            }

            // Index the package
            if (idx != null) {
                idx.indexPackage(pkgMetadata);
            }
        }
    }

    /**
     * Extracts the ExtendedMetaData name from a feature's annotation.
     * <p>
     * ExtendedMetaData annotations are used by XSD-generated models where
     * the XML element/attribute name differs from the Java-friendly EMF feature name.
     * </p>
     *
     * @param feature the structural feature
     * @return the ExtendedMetaData name, or null if not present
     */
    private String getExtendedMetaDataName(EStructuralFeature feature) {
        EAnnotation annotation = feature.getEAnnotation(
                "http:///org/eclipse/emf/ecore/util/ExtendedMetaData");
        if (annotation != null) {
            String name = annotation.getDetails().get("name");
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return null;
    }
}
