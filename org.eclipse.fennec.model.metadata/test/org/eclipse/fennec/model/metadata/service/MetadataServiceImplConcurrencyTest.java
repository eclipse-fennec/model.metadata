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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.fennec.model.metadata.ClassAspect;
import org.eclipse.fennec.model.metadata.ClassMetadata;
import org.eclipse.fennec.model.metadata.FeatureAspect;
import org.eclipse.fennec.model.metadata.FeatureMetadata;
import org.eclipse.fennec.model.metadata.OperationAspect;
import org.eclipse.fennec.model.metadata.OperationMetadata;
import org.eclipse.fennec.model.metadata.PackageAspect;
import org.eclipse.fennec.model.metadata.PackageMetadata;
import org.eclipse.fennec.model.metadata.PackageProfile;
import org.eclipse.fennec.model.metadata.AttributeMetadata;
import org.eclipse.fennec.model.metadata.ReferenceMetadata;
import org.eclipse.fennec.model.metadata.api.AspectProvider;
import org.eclipse.fennec.model.metadata.api.MetadataHandler;
import org.eclipse.fennec.model.metadata.api.MetadataWhiteboard;
import org.junit.jupiter.api.Test;

/**
 * Concurrency stress tests for {@link MetadataServiceImpl}.
 *
 * <p>These tests hammer the de-/attachment paths ({@code registerPackage},
 * {@code unregisterPackage}, {@code registerAspectProvider},
 * {@code unregisterAspectProvider}, {@code setMetadataIndex}/{@code unsetMetadataIndex},
 * {@code addMetadataHandler}/{@code removeMetadataHandler}) from multiple threads at
 * once. All of those read or mutate the shared {@code registry.getPackages()} EMF list,
 * which is not thread-safe.</p>
 *
 * <p>Each test collects every {@link Throwable} thrown on a worker thread and asserts
 * that none occurred, and that the final state is consistent.</p>
 */
class MetadataServiceImplConcurrencyTest {

    private static final int THREADS = 8;

    /** Build a small, unique EPackage (one class with an attribute and an operation). */
    private static EPackage createPackage(int i) {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("p" + i);
        pkg.setNsPrefix("p" + i);
        pkg.setNsURI("http://conc.test/" + i);

        EClass c = EcoreFactory.eINSTANCE.createEClass();
        c.setName("C" + i);
        pkg.getEClassifiers().add(c);

        EAttribute a = EcoreFactory.eINSTANCE.createEAttribute();
        a.setName("name");
        a.setEType(EcorePackage.Literals.ESTRING);
        c.getEStructuralFeatures().add(a);

        EOperation op = EcoreFactory.eINSTANCE.createEOperation();
        op.setName("doIt");
        op.setEType(EcorePackage.Literals.ESTRING);
        c.getEOperations().add(op);

        return pkg;
    }

    /** Runs the given tasks concurrently, gated by a common start barrier, and returns any thrown errors. */
    private static List<Throwable> runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier startGate = new CyclicBarrier(tasks.size());
        try {
            for (Runnable task : tasks) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(errors);
    }

    private static String describe(List<Throwable> errors) {
        StringBuilder sb = new StringBuilder("expected no concurrency errors, but got " + errors.size() + ":\n");
        errors.stream().limit(5).forEach(t -> sb.append("  ").append(t).append('\n'));
        return sb.toString();
    }

    @Test
    void concurrentRegisterDistinctPackages() throws InterruptedException {
        MetadataWhiteboard service = new MetadataServiceImpl();
        int perThread = 60;
        int total = THREADS * perThread;

        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int base = t * perThread;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    service.registerPackage(createPackage(base + i));
                }
            });
        }

        List<Throwable> errors = runConcurrently(tasks);
        assertTrue(errors.isEmpty(), describe(errors));

        // Every distinct package must have landed in the registry exactly once.
        assertEquals(total, service.getRegistry().getPackages().size(),
                "registry lost or duplicated packages under concurrent registration");
        for (int i = 0; i < total; i++) {
            assertNotNull(service.getPackageMetadata("http://conc.test/" + i),
                    "package " + i + " missing after concurrent registration");
        }
    }

    @Test
    void concurrentRegisterPackageAndAspectProvider() throws InterruptedException {
        MetadataWhiteboard service = new MetadataServiceImpl();
        int perThread = 80;

        List<Runnable> tasks = new ArrayList<>();
        // Registrar threads: register distinct packages.
        for (int t = 0; t < THREADS - 2; t++) {
            int base = t * perThread;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    service.registerPackage(createPackage(base + i));
                }
            });
        }
        // Attach/detach threads: repeatedly (un)register a provider — iterates registry.getPackages().
        for (int t = 0; t < 2; t++) {
            int attachId = t;
            tasks.add(() -> {
                AspectProvider provider = new NullAspectProvider("cc" + attachId);
                for (int i = 0; i < 400; i++) {
                    service.registerAspectProvider(provider);
                    service.unregisterAspectProvider(provider);
                }
            });
        }

        List<Throwable> errors = runConcurrently(tasks);
        assertTrue(errors.isEmpty(), describe(errors));
    }

    @Test
    void concurrentRegisterPackageAndSetIndex() throws InterruptedException {
        MetadataWhiteboard service = new MetadataServiceImpl();
        int perThread = 80;

        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS - 1; t++) {
            int base = t * perThread;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    service.registerPackage(createPackage(base + i));
                }
            });
        }
        // Index toggling iterates registry.getPackages() during setMetadataIndex.
        tasks.add(() -> {
            for (int i = 0; i < 400; i++) {
                service.setMetadataIndex(new MapBasedMetadataIndex());
            }
        });

        List<Throwable> errors = runConcurrently(tasks);
        assertTrue(errors.isEmpty(), describe(errors));
    }

    @Test
    void concurrentRegisterPackageAndAddRemoveHandler() throws InterruptedException {
        MetadataWhiteboard service = new MetadataServiceImpl();
        int perThread = 80;

        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS - 1; t++) {
            int base = t * perThread;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    service.registerPackage(createPackage(base + i));
                }
            });
        }
        // addMetadataHandler late-binds by iterating registry.getPackages().
        tasks.add(() -> {
            MetadataHandler handler = new NoopHandler();
            for (int i = 0; i < 400; i++) {
                service.addMetadataHandler(handler);
                service.removeMetadataHandler(handler);
            }
        });

        List<Throwable> errors = runConcurrently(tasks);
        assertTrue(errors.isEmpty(), describe(errors));
    }

    @Test
    void concurrentRegisterAndUnregisterSamePackages() throws InterruptedException {
        MetadataWhiteboard service = new MetadataServiceImpl();
        int count = 200;
        // Pre-register so unregister threads have something to remove.
        List<EPackage> packages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EPackage p = createPackage(i);
            packages.add(p);
            service.registerPackage(p);
        }

        AtomicBoolean go = new AtomicBoolean(true);
        List<Runnable> tasks = new ArrayList<>();
        // Churn: unregister then re-register each package repeatedly.
        for (int t = 0; t < THREADS - 1; t++) {
            int shard = t;
            tasks.add(() -> {
                for (int round = 0; round < 50 && go.get(); round++) {
                    for (int i = shard; i < count; i += (THREADS - 1)) {
                        EPackage p = packages.get(i);
                        service.unregisterPackage(p);
                        service.registerPackage(p);
                    }
                }
            });
        }
        // Concurrent provider attach to iterate the churning registry.
        tasks.add(() -> {
            AspectProvider provider = new NullAspectProvider("cc");
            for (int i = 0; i < 400 && go.get(); i++) {
                service.registerAspectProvider(provider);
                service.unregisterAspectProvider(provider);
            }
        });

        List<Throwable> errors = runConcurrently(tasks);
        assertTrue(errors.isEmpty(), describe(errors));
    }

    // ========================================================================
    // Lightweight test doubles (return null so we exercise iteration, not content)
    // ========================================================================

    private static final class NullAspectProvider implements AspectProvider {
        private final String typeId;

        NullAspectProvider(String typeId) {
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
            return null;
        }
    }

    private static final class NoopHandler implements MetadataHandler {
        @Override
        public void onPackageRegistered(PackageMetadata packageMetadata) {
            // no-op
        }

        @Override
        public void onPackageUnregistered(PackageMetadata packageMetadata) {
            // no-op
        }

        @Override
        public void clear() {
            // no-op
        }
    }
}
