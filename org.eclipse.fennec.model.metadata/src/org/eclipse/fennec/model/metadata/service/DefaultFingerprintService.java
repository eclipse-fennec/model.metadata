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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EParameter;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.fennec.model.metadata.api.FingerprintService;
import org.osgi.service.component.annotations.Component;

/**
 * Default {@link FingerprintService}: builds a canonical textual form of an
 * {@link EPackage}'s <em>semantics</em> (traversing the in-memory model, never the
 * serialized bytes) and hashes it with SHA-256.
 * <p>
 * Canonicalization rules (v1):
 * <ul>
 *   <li>Classifiers of a package are emitted <b>sorted by name</b> — their order in
 *       the package carries no meaning.</li>
 *   <li>Structural features, operations, parameters, supertypes and enum literals are
 *       emitted in <b>declared order</b> — order is meaningful (feature/operation IDs,
 *       inherited feature order).</li>
 *   <li>EAnnotations are emitted sorted by source, their details sorted by key; the
 *       GenModel <b>{@code documentation}</b> detail is excluded, and an annotation
 *       left with no remaining details is dropped entirely — so documentation-only
 *       metadata never affects the fingerprint.</li>
 *   <li>Type references use a stable {@code nsURI#Name} key, so they resolve
 *       identically regardless of resource/serialization state.</li>
 * </ul>
 * Known v1 limitation: generic types ({@code EGenericType}/type parameters) are not
 * yet part of the canonical form.
 *
 * @author Mark Hoffmann
 */
@Component(service = FingerprintService.class)
public class DefaultFingerprintService implements FingerprintService {

    /** Version tag of the canonicalization scheme; bump when the scheme changes. */
    private static final String SCHEME = "fp1";
    private static final String DOCUMENTATION_KEY = "documentation";

    @Override
    public String fingerprint(EPackage ePackage, String... derivationInputs) {
        if (ePackage == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(1024);
        appendPackage(sb, ePackage);
        appendDerivationInputs(sb, derivationInputs);
        return SCHEME + ":" + sha256Hex(sb.toString());
    }

    private void appendPackage(StringBuilder sb, EPackage ePackage) {
        sb.append("P|").append(ePackage.getNsURI()).append('\n');

        List<EClassifier> classifiers = new ArrayList<>(ePackage.getEClassifiers());
        classifiers.sort(Comparator.comparing(EClassifier::getName, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (EClassifier classifier : classifiers) {
            appendClassifier(sb, classifier);
        }
    }

    private void appendClassifier(StringBuilder sb, EClassifier classifier) {
        if (classifier instanceof EClass eClass) {
            sb.append("C|").append(eClass.getName())
                .append("|abstract=").append(eClass.isAbstract())
                .append("|interface=").append(eClass.isInterface());
            // Supertypes in declared order (affects inherited feature order).
            for (EClass sup : eClass.getESuperTypes()) {
                sb.append("|super=").append(typeKey(sup));
            }
            sb.append('\n');
            appendAnnotations(sb, eClass);
            for (EStructuralFeature feature : eClass.getEStructuralFeatures()) {
                appendFeature(sb, feature);
            }
            for (EOperation operation : eClass.getEOperations()) {
                appendOperation(sb, operation);
            }
        } else if (classifier instanceof EEnum eEnum) {
            sb.append("E|").append(eEnum.getName()).append('\n');
            appendAnnotations(sb, eEnum);
            for (EEnumLiteral literal : eEnum.getELiterals()) {
                sb.append("  L|").append(literal.getName())
                    .append("|value=").append(literal.getValue())
                    .append("|literal=").append(literal.getLiteral()).append('\n');
            }
        } else if (classifier instanceof EDataType dataType) {
            sb.append("D|").append(dataType.getName())
                .append("|instanceClassName=").append(dataType.getInstanceClassName())
                .append("|serializable=").append(dataType.isSerializable()).append('\n');
            appendAnnotations(sb, dataType);
        }
    }

    private void appendFeature(StringBuilder sb, EStructuralFeature feature) {
        if (feature instanceof EAttribute attr) {
            sb.append("  A|").append(attr.getName())
                .append("|type=").append(typeKey(attr.getEType()))
                .append("|id=").append(attr.isID())
                .append("|default=").append(attr.getDefaultValueLiteral());
        } else if (feature instanceof EReference ref) {
            sb.append("  R|").append(ref.getName())
                .append("|type=").append(typeKey(ref.getEType()))
                .append("|containment=").append(ref.isContainment())
                .append("|container=").append(ref.isContainer())
                .append("|opposite=").append(oppositeKey(ref.getEOpposite()));
        } else {
            sb.append("  F|").append(feature.getName())
                .append("|type=").append(typeKey(feature.getEType()));
        }
        sb.append("|ordered=").append(feature.isOrdered())
            .append("|unique=").append(feature.isUnique())
            .append("|lb=").append(feature.getLowerBound())
            .append("|ub=").append(feature.getUpperBound())
            .append("|changeable=").append(feature.isChangeable())
            .append("|derived=").append(feature.isDerived())
            .append("|transient=").append(feature.isTransient()).append('\n');
        appendAnnotations(sb, feature);
    }

    private void appendOperation(StringBuilder sb, EOperation operation) {
        sb.append("  O|").append(operation.getName())
            .append("|type=").append(typeKey(operation.getEType()))
            .append("|ordered=").append(operation.isOrdered())
            .append("|unique=").append(operation.isUnique())
            .append("|lb=").append(operation.getLowerBound())
            .append("|ub=").append(operation.getUpperBound());
        // Exceptions: set semantics, order not meaningful -> sorted.
        List<String> exceptions = new ArrayList<>();
        for (EClassifier ex : operation.getEExceptions()) {
            exceptions.add(typeKey(ex));
        }
        exceptions.sort(Comparator.naturalOrder());
        for (String ex : exceptions) {
            sb.append("|throws=").append(ex);
        }
        sb.append('\n');
        // Parameters in declared order (position is meaningful).
        for (EParameter parameter : operation.getEParameters()) {
            sb.append("    p|").append(parameter.getName())
                .append("|type=").append(typeKey(parameter.getEType()))
                .append("|ordered=").append(parameter.isOrdered())
                .append("|unique=").append(parameter.isUnique())
                .append("|lb=").append(parameter.getLowerBound())
                .append("|ub=").append(parameter.getUpperBound()).append('\n');
        }
        appendAnnotations(sb, operation);
    }

    /**
     * Emits annotations sorted by source with details sorted by key, excluding the
     * GenModel {@code documentation} detail. Annotations left without any relevant
     * detail are dropped, so documentation-only metadata is invisible to the fingerprint.
     */
    private void appendAnnotations(StringBuilder sb, EModelElement element) {
        // Sort annotations by source.
        List<EAnnotation> annotations = new ArrayList<>(element.getEAnnotations());
        annotations.sort(Comparator.comparing(EAnnotation::getSource, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (EAnnotation annotation : annotations) {
            Map<String, String> details = new TreeMap<>();
            for (Map.Entry<String, String> entry : annotation.getDetails()) {
                if (!DOCUMENTATION_KEY.equals(entry.getKey())) {
                    details.put(entry.getKey(), entry.getValue());
                }
            }
            if (details.isEmpty()) {
                continue; // documentation-only (or empty) annotation -> not log-relevant
            }
            sb.append("    @|").append(annotation.getSource());
            for (Map.Entry<String, String> entry : details.entrySet()) {
                sb.append('|').append(entry.getKey()).append('=').append(entry.getValue());
            }
            sb.append('\n');
        }
    }

    private void appendDerivationInputs(StringBuilder sb, String... derivationInputs) {
        if (derivationInputs == null || derivationInputs.length == 0) {
            return;
        }
        List<String> inputs = new ArrayList<>();
        for (String input : derivationInputs) {
            if (input != null) {
                inputs.add(input);
            }
        }
        inputs.sort(Comparator.naturalOrder()); // order not significant
        sb.append("I|");
        for (String input : inputs) {
            sb.append(input).append(';');
        }
        sb.append('\n');
    }

    /** Stable {@code nsURI#Name} key for a classifier reference; {@code "null"} when absent. */
    private String typeKey(EClassifier classifier) {
        if (classifier == null) {
            return "null";
        }
        EPackage pkg = classifier.getEPackage();
        String ns = pkg != null ? pkg.getNsURI() : "";
        return ns + "#" + classifier.getName();
    }

    private String oppositeKey(EReference opposite) {
        if (opposite == null) {
            return "";
        }
        return typeKey(opposite.getEContainingClass()) + "/" + opposite.getName();
    }

    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated algorithm on every JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
