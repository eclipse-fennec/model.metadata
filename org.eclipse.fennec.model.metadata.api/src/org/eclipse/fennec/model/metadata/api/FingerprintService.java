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
package org.eclipse.fennec.model.metadata.api;

import org.eclipse.emf.ecore.EPackage;

/**
 * Computes a canonical, content-derived fingerprint of a model version.
 * <p>
 * The fingerprint keys derived artifacts (aspects/profiles) so they can be stored
 * once, reused on re-registration, and replicated across systems. It has three
 * guaranteed properties:
 * <ul>
 *   <li><b>Reproducible</b> — the same {@link EPackage} content plus the same
 *       derivation inputs yields the same value on every node, without coordination
 *       and independent of object identity, {@code hashCode}, registration order,
 *       serialization order/whitespace or wall-clock time.</li>
 *   <li><b>Identifying</b> — structurally different content (added/removed/renamed
 *       classifiers, features, operations; changed types; changed log-relevant
 *       annotations) yields a different value; the nsURI alone is never the key.</li>
 *   <li><b>Canonical</b> — semantically irrelevant differences (e.g. the order in
 *       which classifiers appear in the package, or GenModel {@code documentation}
 *       annotations) do not affect the value.</li>
 * </ul>
 *
 * @author Mark Hoffmann
 */
public interface FingerprintService {

    /**
     * Computes the canonical fingerprint of the given {@link EPackage} together with
     * the (optional) declared derivation inputs.
     * <p>
     * {@code derivationInputs} are opaque, already-canonical tokens (for example
     * {@code "oclEngine=1.2.0"} or {@code "eormConfig=<hash>"}) that are folded into
     * the fingerprint so that a change of a derivation input yields a different
     * fingerprint even for identical Ecore. The order of the tokens is not
     * significant; {@code null} tokens are ignored.
     *
     * @param ePackage the model version to fingerprint; may be {@code null}
     * @param derivationInputs optional canonical input tokens (may be empty or {@code null})
     * @return the fingerprint, or {@code null} if {@code ePackage} is {@code null}
     */
    String fingerprint(EPackage ePackage, String... derivationInputs);
}
